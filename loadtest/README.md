# 부하테스트 (k6) — 백엔드 하드닝 Step 1: 기준선

`ROADMAP-backend-hardening.md` Step 1. **목적은 "before 숫자"를 박는 것** — 이게 있어야
풀·스레드·비동기 튜닝의 효과를 증명한다.

## 왜 두 스크립트인가
- `summary_load.js` → `/api/analytics/summary` (LLM 없음, 빠름). **웹 레이어**(Tomcat 스레드·Hikari 풀·
  DuckDB) 한계 측정. 여기서 p95가 꺾이면 풀/스레드 문제 → Step 2에서 튜닝.
- `rag_ask_load.js` → `/api/rag/ask` (LLM 포함, 느림). **RAG 전체** 지연. Ollama 단일 인스턴스가
  지배적이라 VU 낮게(5). 여기 지연은 대부분 LLM → Step 3(비동기)·Step 5(토큰) 대상.

> 둘을 나눈 이유 자체가 면접 포인트: "튜닝 가능한 변수(풀/스레드)를 외부 LLM 천장과 분리해 측정했다."

## 설치
```bash
brew install k6          # macOS
k6 version
```

## 실행
```bash
# 0) 백엔드(8080) 떠 있어야 함
# 1) 웹 레이어 기준선
k6 run loadtest/summary_load.js
# 2) RAG 전체 기준선
k6 run loadtest/rag_ask_load.js
# 포트 다르면:  k6 run -e BASE_URL=http://localhost:8090 loadtest/summary_load.js
```

## 결과 읽는 법 (이 숫자들을 기록 → before)
k6 요약 끝에:
- `http_req_duration` → **p(95)/p(99)** 가 핵심. 사용자 체감 지연.
- `http_req_failed` → **에러율**. 0이 아니면 풀 고갈/타임아웃 의심(Step 2).
- `iterations` / `http_reqs` 의 초당값 → **처리량(RPS)**.
- `vus` 올라갈 때 p95가 **급격히 꺾이는 지점** = 포화점. 그 VU 수를 적어둔다.

예: "summary는 VU 30까지 p95 120ms 평탄 → 50부터 450ms로 꺾임, 에러 0. /ask는 VU 5에서 p95 12s(LLM)."
이게 **before**. Step 2에서 Hikari `maximum-pool-size`·Tomcat `threads.max` 바꾸고 **재실행 → after** 비교.

## 주의
- 노트북 RAM 빠듯하면(이미 exit 137 겪음) VU를 더 낮춰. 부하테스트가 백엔드를 OOM으로 밀 수 있음.
- `/ask`는 Ollama를 실제로 돌려 느리고 GPU/CPU를 먹음 — 다른 무거운 작업과 동시에 돌리지 말 것.
- 측정 중엔 적재(ingest) 같이 돌리지 말기(변수 오염).

## 다음 (Step 2 미리보기)
포화점/에러가 나온 쪽을 application.yaml에서 조정 → 재측정:
- `spring.datasource.hikari.maximum-pool-size`, `connection-timeout`
- `server.tomcat.threads.max`, `server.tomcat.accept-count`
값별 p95·에러율을 표로 남기면 그대로 면접 자료.
