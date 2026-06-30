#!/usr/bin/env python3
"""
data/vehicle/manuals/ 에 이미 있는 PDF를 **선별 적재**(KB 인제스트).

배경: 폴더에 현대 전 라인업 매뉴얼이 다수(수백 권) 있다. 전부 적재하면 임베딩 수 시간 +
      벡터스토어 폭증이므로, 모델/연식/언어로 골라 넣는다. 기본은 dry-run(미리보기).

규칙 파일명: hyundai_<year>_<model>[_variant]_<projCode>_owners_<KR|EN>.pdf

사용:
    python scripts/ingest_existing_manuals.py                       # 미리보기(아무것도 안 넣음)
    python scripts/ingest_existing_manuals.py --models ioniq5,nexo,palisade --apply
    python scripts/ingest_existing_manuals.py --years 2024,2025,2026 --lang KR --apply
    python scripts/ingest_existing_manuals.py --models tucson --years 2025 --apply

백엔드(8080) 실행 중이어야 함. namespace=vehicle.
"""
import argparse
import glob
import os
import re
import sys
import time
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed

MANUALS_DIR = "data/vehicle/manuals"
# 포트 충돌(Airflow가 8080 점유 등) 대비 — 환경변수로 덮어쓸 수 있게.
#   예: MW_INGEST_URL=http://localhost:8090/api/data/ingest-file python scripts/ingest_existing_manuals.py --apply
INGEST_URL = os.environ.get("MW_INGEST_URL", "http://localhost:8080/api/data/ingest-file")

# 권당 평균 청크(비용 가늠·자동 동시성 산정에 공용). NEXO 303·ST1 446 등의 실측 평균.
CHUNKS_PER_BOOK = 350


def auto_workers(n_books):
    """[보수적] 권 단위 동시 전송 수. 기본 1(직렬) 권장 — 아래 경고 참조.

    ⚠️ 중요(병목 분석 결과, RAG-INGEST-SCALING.md §8):
      백엔드(IngestionService)가 **요청 1건 안에서 이미 임베딩을 8-way 병렬**(ingest.embed.concurrency)
      처리한다. 따라서 이 스크립트에서 권을 또 병렬로 보내면(워커 N) **N권 × 8스레드 + N개 대형 PDF
      동시 파싱**이 되어 힙이 N배로 뛴다 — 실제로 workers=2에서 exit 255(OOM)가 났다.
      → 올바른 병렬 레이어는 '권 fan-out'이 아니라 백엔드의 **embed 풀(ingest.embed.concurrency)**.
        권은 직렬로(1권=PDF 파싱 1개)두어 메모리를 bound하고, 처리량은 embed 풀로 조절한다.

    그래서 자동값도 매우 보수적으로 둔다(대량일 때만 2). 진짜 처리량을 올리려면 권 fan-out 대신
    백엔드 application.yaml 의 ingest.embed.concurrency 를 메모리 예산 안에서 키워라.
    """
    est = n_books * CHUNKS_PER_BOOK
    if est < 30000:   # ~85권 미만: 직렬(권 fan-out 금지 — 백엔드가 이미 내부 병렬)
        return 1
    return 2          # 대량 백필에서만 2권 겹침(그래도 embed.concurrency↓와 병행 권장)

# 멱등 skip 용 pgvector 접속(기본값 = application.yaml 과 동일). 환경변수로 덮어쓰기 가능.
PG = dict(host=os.environ.get("PGVECTOR_HOST", "localhost"),
          port=int(os.environ.get("PGVECTOR_PORT", "55433")),
          dbname=os.environ.get("PGVECTOR_DB", "miniwatson"),
          user=os.environ.get("PGVECTOR_USER", "miniwatson"),
          password=os.environ.get("PGVECTOR_PASSWORD", "miniwatson"))


def already_ingested(namespace="vehicle"):
    """article_vectors 에 이미 적재된 PDF 파일명 집합. url='file://<파일명>#<청크>' 에서 추출."""
    import psycopg2  # 선택 의존성 — --skip-ingested 일 때만 필요
    sql = ("select distinct split_part(replace(url,'file://',''),'#',1) "
           "from article_vectors where namespace=%s")
    with psycopg2.connect(**PG) as conn, conn.cursor() as cur:
        cur.execute(sql, (namespace,))
        return {row[0] for row in cur.fetchall() if row[0]}


def ingest(path, namespace="vehicle"):
    boundary = "----mw" + uuid.uuid4().hex
    with open(path, "rb") as f:
        content = f.read()
    fn = os.path.basename(path)
    pre = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"namespace\"\r\n\r\n{namespace}\r\n"
           f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{fn}\"\r\n"
           f"Content-Type: application/pdf\r\n\r\n").encode("utf-8")
    body = pre + content + f"\r\n--{boundary}--\r\n".encode("utf-8")
    req = urllib.request.Request(INGEST_URL, data=body, method="POST",
                                 headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    with urllib.request.urlopen(req, timeout=900) as r:
        return r.status


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default=None, help="콤마구분 모델 부분일치(파일명) 예: ioniq5,nexo,palisade")
    ap.add_argument("--years", default=None, help="콤마구분 연식 예: 2024,2025,2026")
    ap.add_argument("--lang", default=None, choices=["KR", "EN"], help="언어 필터")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--apply", action="store_true", help="실제 인제스트(미지정 시 dry-run)")
    ap.add_argument("--workers", type=int, default=1,
                    help="동시 전송 권 수. 기본 1(직렬·권장). 0=보수적 자동. "
                         "주의: 백엔드가 요청당 임베딩을 이미 8-way 병렬 → 권 fan-out은 메모리 N배(OOM 위험). "
                         "처리량은 백엔드 ingest.embed.concurrency 로 조절할 것")
    ap.add_argument("--skip-ingested", action="store_true",
                    help="article_vectors 에 이미 적재된 PDF 는 건너뜀(멱등). psycopg2 필요")
    args = ap.parse_args()

    files = sorted(glob.glob(os.path.join(MANUALS_DIR, "hyundai_*_owners_*.pdf")))
    models = [m.strip().lower() for m in args.models.split(",")] if args.models else None
    years = [y.strip() for y in args.years.split(",")] if args.years else None

    sel = []
    for f in files:
        b = os.path.basename(f).lower()
        if args.lang and not b.endswith(f"_owners_{args.lang.lower()}.pdf"):
            continue
        if years and not any(("_" + y + "_") in b for y in years):
            continue
        if models and not any(m in b for m in models):
            continue
        sel.append(f)
    if args.limit > 0:
        sel = sel[:args.limit]

    skipped = 0
    if args.skip_ingested:
        done = already_ingested()
        before = len(sel)
        sel = [f for f in sel if os.path.basename(f) not in done]
        skipped = before - len(sel)

    # 동시성: --workers 미지정(0)이면 배치 규모로 자동 산정, 양수면 그 값으로 고정.
    workers = args.workers if args.workers > 0 else auto_workers(len(sel))
    mode = "고정" if args.workers > 0 else "자동"

    print(f"[ingest] 매칭 {len(sel)}권 / 전체 {len(files)}권"
          + (f" (이미 적재 {skipped}권 skip)" if skipped else "")
          + ("" if args.apply else "  (dry-run — --apply 로 실제 적재)"))
    for f in sel:
        print(("  → " if args.apply else "  · ") + os.path.basename(f))
    print(f"[ingest] 예상 청크 ≈ {len(sel)*CHUNKS_PER_BOOK:,} (권당 ~{CHUNKS_PER_BOOK} 가정) "
          f"→ workers={workers} ({mode})")
    if not args.apply:
        print("[ingest] dry-run — 임베딩 부하 큼. --apply 로 실제 적재(핵심부터 권장).")
        return

    def _one(f):
        try:
            st = ingest(f)
            return (f, True, st)
        except Exception as e:
            return (f, False, e)

    ok = 0
    workers = max(1, workers)
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(_one, f) for f in sel]
        for fut in as_completed(futures):
            f, success, info = fut.result()
            if success:
                ok += 1
                print(f"  ✓ {os.path.basename(f)} [{info}]  ({ok}/{len(sel)})", flush=True)
            else:
                print(f"  ! {os.path.basename(f)} 실패(백엔드 확인): {info}", flush=True)
    print(f"[ingest] 완료: {ok}/{len(sel)} 적재 (namespace=vehicle, workers={workers})")


if __name__ == "__main__":
    main()
