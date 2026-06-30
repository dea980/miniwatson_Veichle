# 매뉴얼 일괄 ingest 성능 진단 (2026-06-24)

전체 owners-manual PDF 458권을 `scripts/ingest_existing_manuals.py --apply` 로
적재하던 중 처리율이 너무 낮아 원인을 측정한 기록.

## 1. 사전 정리 — "다른 컨테이너" 혼동

`docker ps` 에 `aicontentcreator-web-1 / -remotion-1` 이 보였으나, **이 레포와 무관한
별도 프로젝트의 컨테이너**다. 코드 전체 grep 결과 `aicontentcreator` 참조 0건.
같은 도커 데몬에 떠 있던 잔여 컨테이너일 뿐이며, 3000/3001 포트를 점유하고 있었다.
→ `docker stop` 으로 종료. 이 프로젝트 구성요소는 아래 둘뿐이다.

| 컨테이너 | 역할 | 포트 |
|---|---|---|
| `miniwatson-veichle-pg` (pgvector/pg16) | 벡터 DB (`article_vectors`) | 55433→5432 |
| `miniwatson-prometheus-1` | 모니터링 | 9090 |

watson 앱 본체는 컨테이너가 아니라 **로컬 java 프로세스**(`localhost:8080`, Spring Boot)로 떠 있다.

## 2. 환경 확인

- `GET http://localhost:8080/actuator/health` → 200
- 매뉴얼 파일: `data/vehicle/manuals/` 461개, dry-run 매칭 **458권**
- ingest 경로: 스크립트가 권당 1건씩 `POST /api/data/ingest-file` 동기 호출
- 적재 대상 테이블: `article_vectors` (id, article_id, namespace, title, summary, url, ingested_at, embedding `vector(768)`)
- 임베딩 프로바이더: Ollama `granite-embedding:278m` (`application.yaml` → `ollama.embed-model`)
- 청크 임베딩 동시성: `ingest.embed.concurrency=8` (IngestionService 내부 `FixedThreadPool`)

## 3. 측정 결과

90초 윈도우 동안 `article_vectors` 행 수 변화:

```
16:50:45  851  (+0)
16:51:00  851  (+0)
16:51:15  851  (+0)
16:51:31  851  (+0)
16:51:46  878  (+27)   ← 한 권 끝나며 청크 일괄 커밋
16:52:01  899  (+21)
→ 93초간 +48 청크 = 0.51 청크/초
```

- **패턴:** 행이 ~60초 정체했다가 한 권 분량(20~60청크)을 한꺼번에 커밋.
  즉 권당 처리(파싱+임베딩) 후 마지막에 일괄 저장 → **권당 ~40~60초**.
- 첫 권(`hyundai_2007_entourage_owners_EN.pdf`, 8.0MB)은 59청크 생성.

> **⚠ 이 0.51청크/초·10시간 추정은 콜드스타트 이상치였다.** 측정 윈도우가 8MB짜리 거대
> EN 매뉴얼 처리 구간에 걸렸을 뿐이고, **정상 구간 처리율은 ~3~5청크/초**다(§4 재측정).
> 권 크기 편차가 커서 실제 전체 ETA는 수십 분~수 시간 범위. 10시간은 아니다.

## 4. 병목 확정 — 한 권 사이클 CPU 샘플링 (2초 간격)

java(pid 99393)와 Ollama `llama-server`(pid 2478) CPU를 한 권 처리 사이클(~67초) 동안 동시 샘플링:

```
구간 A (~27초)  java 80~182%   ollama 0.3%    ← java 멀티코어 풀가동, Ollama 유휴
구간 B (~40초)  java 8~60%     ollama 2~6%    ← 임베딩 구간
```

**확정된 사실:**
1. **구간 A = java의 PDF 파싱/청킹이 확정적 주요 병목.** Ollama가 노는 동안 java가
   1코어 이상(최대 182%)을 태운다 → 임베딩이 아니라 java 내부 CPU 연산
   (PDFBox 텍스트 추출 + `Chunker.chunk` + `AcronymExpander.expand`).
2. **구간 B 임베딩 시간은 CPU로 측정 불가.** macOS에서 Ollama는 **Metal GPU**로 임베딩하므로
   CPU 사용률(2~6%)엔 거의 안 잡힌다. 따라서 "임베딩이 빠르다"고 단정할 수 없으나,
   **java 파싱/청킹이 사이클의 큰 몫(≥27초/67초)을 차지하는 것은 확실**하다.

> **함의:** 권당 파싱 구간(A) 동안 다른 CPU 코어와 GPU가 논다. 권 1건을 직렬로 보내는 현재
> 방식은 이 유휴 자원을 못 쓴다 → **여러 권 동시 전송이 가장 싼 개선**(§5.1).

## 5. 개선 후보 (효과·싼 것부터)

1. **⭐ 스크립트에서 여러 권 동시 전송** (확정 1순위). §4에서 권당 파싱 구간 동안
   다른 코어·GPU가 노는 게 확인됨. 현재 스크립트는 권 1건씩 직렬 POST → 동시 N권(예: 3~4)
   전송하면 한 권의 파싱(A)과 다른 권의 임베딩(B)이 겹쳐 멀티코어/GPU를 동시에 쓴다.
   서버는 stateless 하므로 코드 변경 없이 스크립트만 손보면 됨. 합산 부하(임베딩 동시성 8 +
   DB 커넥션 풀) 한도만 확인.
2. **PDF 재파싱 회피:** 동일 PDF를 다시 적재할 때 추출 텍스트 캐시 → 구간 A 단축.
3. **재적재 방지(멱등):** 스크립트에 skip 로직이 없어 이미 적재된 권도 다시 넣음
   (`article_vectors`에 사전 행 존재). 이미 적재된 source 스킵으로 불필요한 작업 제거.
4. **임베딩 추가 최적화(필요 시):** Ollama `/api/embed` 배열 입력으로 청크 배치 호출,
   `OLLAMA_NUM_PARALLEL` 상향 + `ingest.embed.concurrency` 동반 조정.

## 6. 병목 확정 — 완료 (§4)

CPU 동시 샘플링으로 확정. 추가로 java 콘솔 로그에서 권당
`... chunk=NNNms (M chunks) embed=NNNms (avg=NNms/chunk)` 라인을 보면
구간 A(chunk)·B(embed) 절대시간을 숫자로 교차검증할 수 있다(현재 stdout 별도 터미널이라 미수집).

## 7. 현재 상태

- ingest 본 작업(`--apply`, 458권)은 백그라운드에서 **계속 진행 중**(중단하지 않음).
- 진행 확인:
  `docker exec miniwatson-veichle-pg psql -U miniwatson -d miniwatson -tAc "select count(*) from article_vectors;"`
- 중단하려면: ingest 파이썬 프로세스 `kill`.

## 부록 — 진단 중 추가한 코드 변경

`scripts/fetch_ingest_kr_manuals.py`, `scripts/ingest_existing_manuals.py` 의 `INGEST_URL` 을
환경변수로 덮어쓸 수 있게 했다(포트 충돌 대비). 기본값은 그대로 `http://localhost:8080`.

```python
INGEST_URL = os.environ.get("MW_INGEST_URL", "http://localhost:8080/api/data/ingest-file")
```
