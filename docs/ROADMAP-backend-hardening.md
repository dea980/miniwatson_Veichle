# 로드맵 — 백엔드 하드닝 (성능·장애·운영 트랙)

주니어 백엔드 면접에서 먹히는 스킬들을 **이 프로젝트의 실제 병목에 적용**하는 트랙.
나중에 읽고 그대로 설명할 수 있게, 각 항목을 (무엇 · 왜=실제 겪은 장애 · 어떻게=이 프로젝트 파일/설정 ·
측정 · 면접 한 줄)로 적는다.

> **대원칙**: 11개를 체크박스로 욱여넣지 않는다. 면접관은 "왜 넣었어?"를 찌른다 — 답이 "넣고 싶어서"면
> 마이너스, "부하테스트 X에서 이 병목이 나와서"면 플러스. **측정 → 변경 → 재측정 → 문서화**가 전부.
> 다행히 이번 개발에서 진짜 장애들(500·broken pipe·exit 137·동기 임베딩)을 이미 겪어 근거가 있다.

---

## 0. 매핑 — 11항목 × 이 프로젝트의 훅 × 판정
| 항목 | 이 프로젝트의 실제 훅 (이미 겪음) | 판정 |
|---|---|---|
| 비동기/동기 외부호출(#7) | 임베딩이 청크당 **동기 HTTP** = 주 병목(RAG-INGEST §1B) | ⭐ 1순위 |
| connection pool 설정(#8) | **Hikari 스레드 고갈**로 리포트 500 났던 사건 | ⭐ 1순위 |
| 외부호출 connection/max-route(#10) | Ollama RestTemplate 풀 · 적재 중 **broken pipe** | ⭐ 1순위 |
| k6 성능테스트(#9) | /ask·적재 부하 → 풀·스레드 한계 측정 | ⭐ 강함 |
| EXPLAIN 쿼리개선(#1) | pgvector HNSW·DuckDB 집계 `EXPLAIN ANALYZE` | ⭐ 강함 |
| token/context 절약(#5) | RAG 청크 1400·소스 600자·프롬프트 길이 로깅 | ⭐ 강함 |
| 로컬 캐시(#2) | compute-once+cache(요약·리포트) 이미 함 → Caffeine 격상 | ✅ 적합 |
| MCP 서버 적용(#6) | 차종 KB·집계를 MCP 툴로 노출 | ✅ 적합(임팩트 큼) |
| tcpdump 장애분석(#4) | 방금 **broken pipe**를 tcpdump로 잡기 | ◐ 스킬(상황적) |
| thread 수치 튜닝(#11a) | Tomcat 스레드풀 수치 | ◐ 작게 |
| Redis(#3) | 단일노드 → 억지. DB 캐시가 이미 영속 | △ 명분 있을 때만 |
| Netty(WebFlux) 교체(#11b) | 리액티브 전면 재작성 = 과함 | △ 학습용 아니면 보류 |

---

## 0.5 측정된 기준선 (before · 2026-06-25, k6, 24GB 맥북·Temurin21·qwen3:8b)
| 경로 | 부하 | p95 | 에러율 | 처리량 | 해석 |
|---|---|---|---|---|---|
| `/api/analytics/summary` (웹) | 60 VU | **56ms** | 0% | 52 RPS | 웹 레이어(Tomcat·Hikari·DuckDB) **여유 큼 → 튜닝 대상 아님** |
| `/api/rag/ask` (RAG+LLM, **캐시 전**) | 5 VU | **57s** | 6.66%(타임아웃) | 0.1 RPS | **단일 Ollama 직렬화 = 병목** |
| `/api/rag/ask` (**답변 캐시 후**, Step A) | 5 VU | **3.94ms** | 0.00% | **1,089 RPS** | 반복 질의 캐시 HIT — ≈14,000배·처리량 1만배 |

- 단일요청 `min=24s` → **모델 자체 속도**(동시성 무관). `p95 57s @5VU` → **Ollama 큐잉**(동시성 천장).
- 결론: 풀/스레드(Step2)는 **측정상 불필요**. 가치는 **LLM 경로**(아래 §1 Step3·5 + 답변 캐시)에 있다.
- ⇒ "측정해보니 웹은 멀쩡해서 안 건드렸다"가 정답(premature optimization 회피). after는 같은 k6로 재측정해 비교.
- **캐시 after 정직한 해석**: 3.94ms는 *반복(동일) 질의 HIT* 성능이다. **신규 질의는 여전히 LLM 비용(24~60s)**을 낸다.
  즉 캐시는 "반복 트래픽 제거"이지 "LLM을 빠르게"가 아님 — 면접에선 이 구분을 명확히(과장 금지).
  다음 레버(컨텍스트 축소·작은 모델·스트리밍)가 *신규 질의* 지연을 줄이는 쪽.

---

## 1. 7스텝 서사 (하나의 스토리: 측정→튜닝→비동기→쿼리/토큰→증명)

### Step 1 — k6로 기준선 측정 (#9)  ※ 모든 개선의 출발점
- **왜**: 숫자 없이 "개선했다"는 못 한다. before를 먼저 박는다.
- **어떻게**: `POST /api/rag/ask`(질문 고정), `GET /api/analytics/summary`에 k6 스크립트.
  VU(가상유저) 10→50→100 ramp, p95 지연·에러율·throughput 기록.
  ```js
  // k6 run rag_ask.js  (예시 골격)
  import http from 'k6/http'; import { check } from 'k6';
  export const options = { stages: [{duration:'30s',target:20},{duration:'1m',target:50}] };
  export default () => {
    const r = http.post('http://localhost:8080/api/rag/ask',
      JSON.stringify({question:'안전벨트 경고등은 언제 울리나요?', namespace:'vehicle'}),
      {headers:{'Content-Type':'application/json'}});
    check(r, {'200': x => x.status===200});
  };
  ```
- **측정**: p50/p95/p99, 에러율, RPS. 어디서 꺾이는지(풀? 스레드? Ollama?).
- **면접 한 줄**: "부하를 단계적으로 올려 p95가 꺾이는 지점과 원인(풀/스레드/외부호출)을 분리했다."

### Step 2 — connection pool · thread 튜닝 (#8 · #11a · #10)
- **왜(실제 장애)**: 리포트 생성이 **30초 재생성 × 동시 + Hikari 풀 고갈**로 500. Step1에서 같은 포화 재현됨.
- **어떻게**:
  - **Hikari**(application.yaml): `spring.datasource.hikari.maximum-pool-size`, `connection-timeout`,
    `leak-detection-threshold`. 풀이 작으면 대기→타임아웃, 크면 DB 과부하 — k6로 sweet spot 찾기.
  - **Tomcat 스레드**: `server.tomcat.threads.max`(기본 200), `accept-count`, `max-connections`.
    동시 요청 vs 워커 수 관계를 수치로.
  - **Ollama 외부호출 풀(#10)**: `IngestionService`/`RagService`의 RestTemplate을 **풀링 HttpClient**로 교체 —
    `PoolingHttpClientConnectionManager` 의 `maxTotal`, `defaultMaxPerRoute`(단일 Ollama라 route 1개에 몰림).
    적재 중 **broken pipe**는 커넥션 재사용/타임아웃 설정 부재의 징후.
- **측정**: 풀/스레드 값별로 k6 재실행 → p95·에러율 비교 표.
- **면접 한 줄**: "Hikari 풀과 Tomcat 스레드, 외부호출 커넥션 풀을 부하테스트로 튜닝해 500/broken pipe를 제거했다."

### Step 3 — 임베딩 동기 → 비동기/배치 (#7)  ※ 주 병목
- **왜**: 청크당 동기 Ollama 콜(권당 300~450콜). `IngestionService`가 이미 8-way 풀이지만 요청 경로에 묶임.
- **어떻게**: (a) 임베딩을 **요청 경로 밖**(배치 워커/큐)으로, (b) 호출을 **논블로킹**(WebClient) 또는
  배치 임베딩 API로. 동시성은 §8.5대로 **권 fan-out 아니라 임베딩 풀**에서.
- **측정**: chunks/s, 권당 소요 before/after. (Dagster materialization metadata의 `seconds`도 근거.)
- **면접 한 줄**: "I/O-bound 외부호출을 동기에서 비동기/배치로 바꿔 적재 처리량을 N배 올렸다 — 단 병렬의
  올바른 레이어(임베딩 풀)에 뒀고, 권 fan-out은 OOM이라 피했다(측정 4 OK/8 crash)."

### Step 4 — EXPLAIN으로 쿼리 개선 (#1)
- **왜**: 검색·집계가 느려지면 어디서? 추측 말고 실행계획.
- **어떻게**:
  - **pgvector**: `EXPLAIN ANALYZE SELECT ... ORDER BY embedding <=> ?::vector LIMIT k;` —
    HNSW 인덱스 타는지, `SET hnsw.ef_search` 값별 recall/지연 트레이드오프.
  - **DuckDB**: 우선순위·핫스팟 집계 `EXPLAIN ANALYZE`로 스캔/조인 비용.
- **측정**: 인덱스 적용 전/후 지연, ef_search별 p95 vs recall.
- **면접 한 줄**: "EXPLAIN ANALYZE로 ANN 쿼리가 HNSW를 타는지 확인하고 ef_search로 지연·정확도를 조율했다."

### Step 5 — token/context 절약 (#5) · 로컬 캐시 (#2)
- **왜**: RAG는 LLM 토큰이 비용·지연. 캐시는 반복 연산 제거.
- **어떻게**:
  - **token**: 청크 1400·소스 표출 600자(이미)·프롬프트 길이 로깅(이미) → top-k·rerank로 컨텍스트 최소화,
    토큰 수 측정(before/after).
  - **로컬 캐시**: 지금은 DB(`GeneratedReport`) compute-once. 핫 읽기(models·summary·요약)는
    **Caffeine** 인메모리 캐시로 한 단계 더(TTL·maximumSize). `@Cacheable`.
- **측정**: 캐시 hit율, 평균 응답 지연, 프롬프트 토큰 수.
- **면접 한 줄**: "RAG 컨텍스트를 top-k·청크 크기로 토큰 절약하고, 반복 연산은 2단(인메모리 Caffeine + DB 영속) 캐시로 줄였다."

### Step 6 — MCP 서버로 노출 (#6)  ※ 별도 임팩트 트랙
- **왜**: 차종 KB·집계·케이스를 **표준 MCP 툴**로 노출하면 Claude/다른 에이전트가 직접 호출.
- **어떻게**: FastMCP(파이썬) 또는 Java MCP — 툴: `search_manual(car,year,q)`,
  `model_hotspots(car,year)`, `case_report(id)`. 기존 REST 위에 얇게.
- **면접 한 줄**: "도메인 기능을 MCP 툴로 노출해 에이전트 상호운용을 만들었다."

### Step 7 — tcpdump로 장애 분석 (#4)  ※ 스킬, 위 과정에서 상황적으로
- **왜/어떻게**: Step2~3에서 **broken pipe / 커넥션 리셋**이 재현될 때, 그 자리에서:
  ```bash
  sudo tcpdump -i lo0 'tcp port 8080 or tcp port 11434' -w ingest.pcap   # 백엔드·Ollama 트래픽
  # Wireshark로 RST/재전송/타임아웃 지점 확인 → 커넥션 풀 설정과 대조
  ```
- **면접 한 줄**: "broken pipe를 tcpdump로 캡처해 RST 발생 지점을 확인하고 커넥션 keepalive/타임아웃을 고쳤다."

---

## 2. 보류 (명분 설 때만)
- **Redis(#3)**: 단일노드 + DB 영속 캐시가 이미 있어 *지금은 억지*. 정당해지는 조건 —
  (a) 백엔드 다중 인스턴스로 캐시 공유, (b) 레이트리밋/세션, (c) 분산 락. 이 중 하나를 실제로 만들면 그때.
- **Netty/WebFlux(#11b)**: 리액티브 전면 재작성은 이 규모에 과함. *학습 목적*이면 ingest 외부호출만
  WebClient(논블로킹)로 부분 적용(Step3)하는 게 ROI 좋음 — 전면 교체는 비추.

---

## 3. 근거 인덱스 — 이번 개발에서 실제 겪은 장애 (면접 스토리 원천)
| 장애 | 무엇이었나 | 어느 스텝의 근거 |
|---|---|---|
| 리포트 **500** | 30s 재생성 × 동시 + Hikari 스레드 고갈 + 더블파이어 | Step2(풀·스레드) |
| 적재 **broken pipe** | Ollama 커넥션 재사용/타임아웃 미설정 의심 | Step2(#10)·Step7(tcpdump) |
| **exit 255** | JVM 힙 OOM(박싱 임베딩 인메모리) | Step3(비동기)·근본=float[] |
| **exit 137** | 머신 RAM 고갈(부팅 + 적재 누적) | 운영=작은 wave, 근본=인메모리 제거 |
| 동기 임베딩 | 청크당 1 HTTP, 요청 경로에 묶임 | Step3(주 병목) |
| 4 OK / 8 crash | 임베딩 동시성 실측 천장 | Step3(올바른 레이어) |

→ 상세 분석은 `RAG-INGEST-SCALING.md`(§1~8), 결정 로그는 `SESSION-DECISIONS-2026-06-25.md`.

## 의존 순서
Step1(k6 기준선) → 2(풀·스레드) → 3(비동기) → 4(EXPLAIN) → 5(토큰·캐시). 6(MCP)·7(tcpdump)은 병행 가능.
**측정 없이 다음 스텝 금지** — 이 트랙의 가치는 코드가 아니라 *before/after 숫자*다.
