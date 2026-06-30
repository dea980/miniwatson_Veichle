"""
Vehicle 매뉴얼 적재 — Dagster asset-per-book 파이프라인 (오케스트레이션 레이어).

설계 원칙 (RAG-INGEST-SCALING.md §8):
  - 무거운 연산(PDF 추출·청킹·임베딩·pgvector upsert)은 **기존 Spring 백엔드 엔드포인트**
    (POST /api/data/ingest-file)에 그대로 둔다. Dagster가 그걸 다시 구현하지 않는다.
  - Dagster는 그 위에서 **권 1개 = 1 파티션(asset)** 으로:
      · 멱등/재개  — 이미 적재된 권(pgvector에 chunks 존재)은 건너뜀(SKIP).
      · 재시도     — 실패한 권만 RetryPolicy로 N회.
      · 관측/시간  — 권별 HTTP 상태·소요(초)·청크수를 materialization metadata로 기록(UI에서 확인).
      · 백필       — 연식·차종별 파티션 선택 실행.
  - 즉 처리량/메모리 천장(단일 Ollama·인프로세스 메모리)은 DAG로 못 푼다(§8.1). DAG는 "458권
    적재를 운영 가능하게" 만드는 레이어다.

실행:
  pip install dagster dagster-webserver psycopg2-binary
  # 백엔드(8080) + pgvector(55433) 떠 있는 상태에서:
  cd pipelines/dagster && dagster dev
  # 브라우저 UI에서 asset 'manual_ingested' 를 골라 Materialize(전체) 또는 파티션 선택 백필.

환경변수:
  MW_MANUALS_DIR   매뉴얼 디렉터리 (기본 ../../data/vehicle/manuals)
  MW_INGEST_URL    적재 엔드포인트 (기본 http://localhost:8080/api/data/ingest-file)
  MW_NAMESPACE     네임스페이스 (기본 vehicle)
  PGVECTOR_*       pgvector 접속(멱등 skip용) — 기본 application.yaml 과 동일
"""
import glob
import os
import time
import uuid
import urllib.request

from dagster import (
    asset, Definitions, StaticPartitionsDefinition, RetryPolicy,
    AssetExecutionContext, MetadataValue,
)

# 리포 루트 기준 경로 (이 파일: <repo>/pipelines/dagster/definitions.py)
_REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MANUALS_DIR = os.environ.get("MW_MANUALS_DIR", os.path.join(_REPO, "data", "vehicle", "manuals"))
INGEST_URL = os.environ.get("MW_INGEST_URL", "http://localhost:8080/api/data/ingest-file")
NAMESPACE = os.environ.get("MW_NAMESPACE", "vehicle")

PG = dict(
    host=os.environ.get("PGVECTOR_HOST", "localhost"),
    port=int(os.environ.get("PGVECTOR_PORT", "55433")),
    dbname=os.environ.get("PGVECTOR_DB", "miniwatson"),
    user=os.environ.get("PGVECTOR_USER", "miniwatson"),
    password=os.environ.get("PGVECTOR_PASSWORD", "miniwatson"),
)


def _manual_files():
    """권=파티션 키. 파일명 일관 규칙(hyundai_*_owners_*.pdf)만 대상."""
    return sorted(
        os.path.basename(p)
        for p in glob.glob(os.path.join(MANUALS_DIR, "hyundai_*_owners_*.pdf"))
    )


# 권 1권 = 파티션 1개. (디렉터리에 파일이 늘면 dagster dev 재시작 시 반영)
manuals_partitions = StaticPartitionsDefinition(_manual_files() or ["__none__"])


def _already_ingested(filename: str) -> bool:
    """pgvector 에 이 권의 chunks 가 이미 있나(url='file://<파일명>#<청크>'). 멱등 skip 용.
    psycopg2 없거나 pg 접속 실패면 False 반환 → 그냥 적재 진행(스킵 최적화만 비활성)."""
    try:
        import psycopg2
    except ImportError:
        return False
    sql = "select 1 from article_vectors where namespace=%s and url like %s limit 1"
    try:
        with psycopg2.connect(**PG) as conn, conn.cursor() as cur:
            cur.execute(sql, (NAMESPACE, f"file://{filename}#%"))
            return cur.fetchone() is not None
    except Exception:
        return False


def _ingest(path: str) -> int:
    """stdlib multipart POST → /api/data/ingest-file (file, namespace). HTTP 상태코드 반환."""
    boundary = "----mw" + uuid.uuid4().hex
    with open(path, "rb") as f:
        content = f.read()
    fn = os.path.basename(path)
    pre = (
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"namespace\"\r\n\r\n{NAMESPACE}\r\n"
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{fn}\"\r\n"
        f"Content-Type: application/pdf\r\n\r\n"
    ).encode("utf-8")
    body = pre + content + f"\r\n--{boundary}--\r\n".encode("utf-8")
    req = urllib.request.Request(
        INGEST_URL, data=body, method="POST",
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
    )
    with urllib.request.urlopen(req, timeout=900) as r:
        return r.status


@asset(
    partitions_def=manuals_partitions,
    retry_policy=RetryPolicy(max_retries=2, delay=5),   # 실패 권만 재시도(백엔드 일시 부하/타임아웃 대비)
    group_name="vehicle_ingest",
    description="매뉴얼 1권을 KB(pgvector)에 적재. 이미 적재된 권은 SKIP(멱등).",
)
def manual_ingested(context: AssetExecutionContext) -> None:
    fn = context.partition_key
    path = os.path.join(MANUALS_DIR, fn)

    if _already_ingested(fn):
        context.log.info(f"SKIP (이미 적재): {fn}")
        context.add_output_metadata({"file": MetadataValue.text(fn), "skipped": MetadataValue.bool(True)})
        return

    t0 = time.time()
    status = _ingest(path)
    dt = round(time.time() - t0, 1)
    size_mb = round(os.path.getsize(path) / 1024 / 1024, 1)
    context.log.info(f"INGEST {fn}: HTTP {status} · {dt}s · {size_mb}MB")
    # 권별 시간/상태/용량을 UI metadata로 — '시간 확인'이 여기서 보인다.
    context.add_output_metadata({
        "file": MetadataValue.text(fn),
        "http_status": MetadataValue.int(status),
        "seconds": MetadataValue.float(dt),
        "size_mb": MetadataValue.float(size_mb),
        "skipped": MetadataValue.bool(False),
    })


defs = Definitions(assets=[manual_ingested])
