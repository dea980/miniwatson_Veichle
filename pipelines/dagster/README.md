# Vehicle 매뉴얼 적재 — Dagster 파이프라인

458권 매뉴얼 적재를 "운영 가능"하게 만드는 **오케스트레이션 레이어**. 권 1권 = 1 asset 파티션.

## 왜 Dagster인가 (한 줄)
> 처리량/메모리 천장(단일 Ollama·인프로세스 메모리)은 DAG로 못 푼다(RAG-INGEST-SCALING.md §8.1).
> DAG가 주는 건 **멱등·재시도·재개·관측·백필** — 즉 대규모 적재의 *운영*이다.

기존 `scripts/ingest_existing_manuals.py`(수동 `--skip-ingested`)가 하던 "재개"를 정식화한 것:

| 수동 스크립트 | Dagster (여기) |
|---|---|
| `--skip-ingested` 수동 재개 | 파티션 materialization 상태로 **자동 재개** + 권별 pgvector SKIP |
| 죽으면 직접 재실행 | `RetryPolicy(max_retries=2)` — **실패 권만 재시도** |
| 로그 tail | UI에서 **권별 상태·소요(초)·용량 관측** |
| 한 번에 다 | 연식·차종 **파티션 선택 백필** |

## 설계
```
[Dagster: 권=asset 파티션]                 [Spring 백엔드 8080]            [pgvector 55433]
 manual_ingested(partition=파일명)  ──POST /api/data/ingest-file──▶  extract→chunk→embed→upsert
   ├ _already_ingested(fn)?  ──조회──────────────────────────────────────────────▶ (SKIP 판단)
   ├ 아니면 적재 + 시간측정
   └ metadata: http_status·seconds·size_mb  (UI에 표시)
```
- **무거운 연산은 백엔드에 그대로.** Dagster는 재구현하지 않고 엔드포인트를 호출(얇은 오케스트레이터).
- **멱등 2중**: (1) Dagster 파티션 materialization 상태, (2) pgvector 행 존재 검사(`_already_ingested`).
- **시간 확인**: 각 권의 `seconds` 가 materialization metadata에 기록 → UI Asset 화면에서 권별·전체 추세 확인.

## 실행
```bash
# 0) 사전: 백엔드(8080) + pgvector(55433) 떠 있어야 함 (HotSpot JVM 권장)
pip install -r pipelines/dagster/requirements.txt

# 1) Dagster UI 기동
cd pipelines/dagster && dagster dev          # http://localhost:3000 (Dagster UI)

# 2) UI에서 asset 'manual_ingested' 선택 → Materialize
#    - 전체: 모든 파티션
#    - 백필: 특정 파티션(연식·차종) 선택 → 부분 실행
```
> ⚠️ Dagster UI 기본 포트도 3000 — vehicle 프론트(3000)와 겹친다. 동시에 띄우면
> `dagster dev -p 3070` 처럼 옮기거나 프론트를 잠시 내릴 것. (RUNBOOK 포트맵 참고)

## 환경변수
| 변수 | 기본 | 용도 |
|---|---|---|
| `MW_MANUALS_DIR` | `../../data/vehicle/manuals` | 매뉴얼 디렉터리 |
| `MW_INGEST_URL` | `http://localhost:8080/api/data/ingest-file` | 적재 엔드포인트 |
| `MW_NAMESPACE` | `vehicle` | 네임스페이스 |
| `PGVECTOR_*` | application.yaml 동일 | 멱등 SKIP 조회 |

## 동시성 (중요)
- **권 fan-out을 Dagster 동시성으로 올리지 말 것** — 단일 Ollama + 백엔드 인프로세스 메모리라
  동시 권 N개 = PDF 파싱 N개 + 임베딩 N×풀 = OOM(§8.5, 실측 4 OK/8 crash).
- 처리량은 **백엔드 `ingest.embed.concurrency`(권당 임베딩 풀)** 로 조절. Dagster는 기본 직렬(권 1개씩) 유지.
- 즉 Dagster 멀티프로세스 실행기를 켜더라도 **max_concurrent=1** 로 두는 게 이 단일노드의 안전값.

## 면접 포인트
- "대규모 적재를 어떻게 운영?" → 권=asset 멱등 파이프라인 + 재시도/재개/백필/관측. 단 **병렬은 올바른
  레이어(백엔드 embed 풀)에**, DAG는 운영만. "병렬을 어디 두느냐 > 병렬을 하느냐"(§8.5).
- "왜 Airflow/Spark 아님?" → 단일노드·I/O bound(단일 Ollama)엔 과중. Dagster asset 모델이 멱등·백필에 적합.
