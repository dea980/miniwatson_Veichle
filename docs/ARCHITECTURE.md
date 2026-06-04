# MiniWatson — Architecture

> watsonx의 data · ai · governance 3-layer 모델을 작은 단일 Spring Boot 프로세스 안에 매핑한 구조.

---

## 1. Layered View

```
┌──────────────────────────────────────────────────────────────────────┐
│                         CLIENT (browser)                             │
│        index.html  +  app.js  +  styles.css  (IBM Carbon-style)      │
└───────────────────────────────┬──────────────────────────────────────┘
                                │ HTTP / JSON  (fetch)
                                │
┌───────────────────────────────▼──────────────────────────────────────┐
│  PRESENTATION LAYER  —  controller/                                  │
│                                                                      │
│   HelloController        AskController       RagController           │
│   /api/hello             /api/ask            /api/rag/ask            │
│   /api/version                                                       │
│                                                                      │
│   DataController                          GovernanaceController      │
│   /api/data/ingest                        /api/governance/logs       │
│   /api/data/ingest-batch                                             │
│   /api/data/articles                                                 │
│   /api/data/count                                                    │
└───────────────────────────────┬──────────────────────────────────────┘
                                │ (Spring DI: constructor injection)
                                │
┌───────────────────────────────▼──────────────────────────────────────┐
│  SERVICE LAYER  —  service/                                          │
│                                                                      │
│   ┌───────────────────────────────────────────────────────────────┐  │
│   │  AI LAYER  (watsonx.ai analog)                                │  │
│   │  ───────────────────────────────────────────────────────────  │  │
│   │   OllamaService     — chat (gemma4, think:false)              │  │
│   │   EmbeddingService  — 768-dim vectors (nomic-embed-text)      │  │
│   │   RagService        — retrieval + augmentation orchestrator   │  │
│   └───────────────────────────────────────────────────────────────┘  │
│                                                                      │
│   ┌───────────────────────────────────────────────────────────────┐  │
│   │  DATA LAYER  (watsonx.data analog)                            │  │
│   │  ───────────────────────────────────────────────────────────  │  │
│   │   IngestionService    — Wikipedia → Article → embedding       │  │
│   │   ArticleParquetStore — Parquet/Avro persistent store         │  │
│   │   ArticleStore        — (legacy JSON store, kept for ref)     │  │
│   └───────────────────────────────────────────────────────────────┘  │
│                                                                      │
│   ┌───────────────────────────────────────────────────────────────┐  │
│   │  GOVERNANCE LAYER  (watsonx.governance analog)                │  │
│   │  ───────────────────────────────────────────────────────────  │  │
│   │   QueryLog            — JPA entity                            │  │
│   │   QueryLogRepository  — Spring Data JPA                       │  │
│   │   (audit hook lives inside OllamaService.ask())               │  │
│   └───────────────────────────────────────────────────────────────┘  │
└───────────────────────────────┬──────────────────────────────────────┘
                                │
                                │ external I/O
                                ▼
┌──────────────────────────────────────────────────────────────────────┐
│  EXTERNAL / PERSISTENCE                                              │
│   • Ollama daemon         http://localhost:11434                     │
│   • Wikipedia REST        en.wikipedia.org/api/rest_v1/page/summary  │
│   • Parquet file          ./data/articles.parquet                    │
│   • H2 DB                 in-memory (dev) / file (demo)              │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 2. Why these 3 layers (watsonx mapping)

| watsonx 컴포넌트 | 본 프로젝트 매핑 | 핵심 의도 |
|---|---|---|
| **watsonx.ai** | `OllamaService`, `EmbeddingService`, `RagService` | LLM 추론·임베딩·증강된 prompt 조립 |
| **watsonx.data** | `IngestionService`, `ArticleParquetStore`, `article.avsc` | 외부 소스 → 정규화된 도메인 → columnar storage |
| **watsonx.governance** | `QueryLog`, `QueryLogRepository`, `OllamaService` 내 hook | 모든 LLM 호출의 audit (모델·latency·timestamp) |

**핵심 통찰**: governance는 별도 레이어가 아니라 **AI 호출 경로에 박힌 cross-cutting hook**. `OllamaService.ask()` 안에서 `QueryLogRepository.save()` 가 자동 실행되어 어떤 컨트롤러가 호출하든 누락되지 않음.

---

## 3. Component Diagram (DI Graph)

```
                            MiniwatsonApplication
                                    │  (Spring Boot context)
                                    ▼
        ┌──────────────────────────────────────────────────┐
        │  Controllers (전부 @RestController)               │
        │                                                  │
        │  HelloController       (no deps)                 │
        │                                                  │
        │  AskController     ──► OllamaService             │
        │                                                  │
        │  RagController     ──► RagService                │
        │                                                  │
        │  DataController    ──► IngestionService          │
        │                    ──► ArticleParquetStore       │
        │                                                  │
        │  GovernanaceController ──► QueryLogRepository    │
        └──────────────────────────────────────────────────┘
                                    │
        ┌───────────────────────────┴──────────────────────┐
        ▼                                                  ▼
  Services                                          Repositories / Stores

  OllamaService                                     ArticleParquetStore
   ├── @Value ollama.url                             ├── Schema (article.avsc)
   ├── @Value ollama.chat-model                      ├── saveAll / loadAll / save
   ├── @Value ollama.num-predict                     └── (./data/articles.parquet)
   ├── RestTemplate                                  
   └── QueryLogRepository  ─────────► H2 DB         QueryLogRepository (JPA)
                                                     └── query_log table
  EmbeddingService
   ├── @Value ollama.url
   ├── @Value ollama.embed-model
   └── RestTemplate

  IngestionService
   ├── ArticleParquetStore
   └── EmbeddingService

  RagService
   ├── EmbeddingService
   ├── ArticleParquetStore
   └── OllamaService    (= governance audit이 자동 트리거됨)
```

`ArticleStore` (JSON 버전) 도 빈으로 등록은 되어 있지만 어디서도 주입받지 않음. Day 4b에서 Parquet로 마이그레이션 후 reference 용으로 남김. 안전하게 제거 가능 (단, [SDS.md §6 "ArticleStore 제거 절차"](./SDS.md) 참고).

---

## 4. Data Flow — Ingestion

```
 user
  │
  │ POST /api/data/ingest?title=Retrieval-augmented_generation
  ▼
 DataController.ingest(title)
  │
  ▼
 IngestionService.ingest(title)
  │
  │  ┌──────────── Wikipedia REST ────────────┐
  │  │ GET .../page/summary/{title}           │
  │  │ Header: User-Agent: MiniWatson/1.0 ... │   ← 없으면 403
  │  └────────────────────────────────────────┘
  │
  │  build Article(title, summary, url, ingestedAt)
  │
  │  ┌──────────── EmbeddingService ──────────┐
  │  │ POST localhost:11434/api/embed         │
  │  │ {"model":"nomic-embed-text",           │
  │  │  "input":"Title. Summary..."}          │
  │  │ ← List<List<Float>> (1 × 768)          │
  │  └────────────────────────────────────────┘
  │
  │  article.embedding = embeddings.get(0)
  │
  ▼
 ArticleParquetStore.save(article)
  │  ├ loadAll()                       ← 기존 articles 메모리 로드
  │  ├ article.id = size + 1            ← 단순 auto-increment
  │  ├ append article
  │  └ saveAll(articles)
  │      ├ 기존 .parquet 삭제           ← Parquet은 in-place append 불가
  │      └ AvroParquetWriter (SNAPPY)
  │
  ▼
 → 응답: Article JSON (embedding 필드는 @JsonProperty(WRITE_ONLY)로 숨김)
```

---

## 5. Data Flow — RAG Q&A

```
 user
  │
  │ POST /api/rag/ask  { "question": "What is RAG?" }
  ▼
 RagController.ask(req)
  │
  ▼
 RagService.ask(question)
  │
  │ ① EmbeddingService.embed(question)   → questionVec (768)
  │
  │ ② ArticleParquetStore.loadAll()      → List<Article> (전체)
  │
  │ ③ cosineSimilarity(questionVec, a.embedding) 정렬
  │    .limit(TOP_K = 2)                  → topArticles
  │
  │ ④ prompt =                            "Based on this context:\n"
  │                                        + "- {title}: {summary}\n" × top-K
  │                                        + "\nAnswer the question concisely: " + question
  │
  │ ⑤ OllamaService.ask(prompt)
  │       ├ POST localhost:11434/api/generate
  │       │   {"model":"gemma4","prompt":"...","stream":false,
  │       │    "think":false,"options":{"num_predict":500}}
  │       ├ latency = now - startTime
  │       └ QueryLogRepository.save(QueryLog{question,answer,model,latencyMs})
  │           └──► H2 query_log table (governance audit)
  │
  ▼
 RagResult(answer, sources=topArticles)
  │
  ▼
 → 응답: { "answer": "...", "sources": [Article, Article] }
```

핵심: governance log는 `OllamaService.ask()` 단계에서만 기록된다. 즉 `/api/ask` (direct) 와 `/api/rag/ask` (RAG) 둘 다 audit되지만, ingest 단계의 embedding 호출은 audit되지 않는다. 의도된 분리 — "사용자 질의 = audit 대상", "내부 indexing = 대상 아님".

---

## 6. Configuration Strategy

Spring profile 기반의 환경 분리:

```
application.yaml      ← 공통 + ollama.* + active profile (default: dev)
  │
  ├── application-dev.yaml    H2 in-memory, create-drop, h2-console on
  ├── application-demo.yaml   H2 file(./data/miniwatson), ddl-auto:update
  └── application-prod.yaml   ${DATABASE_URL}, ddl-auto:validate
```

런타임 override (코드 수정 없이):
```
OLLAMA_URL=http://other-host:11434 \
OLLAMA_CHAT_MODEL=qwen3.6 \
OLLAMA_NUM_PREDICT=1000 \
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

`@Value("${ollama.url}")` 등으로 모든 외부 endpoint/모델명을 yaml에서 주입. 하드코딩 없음 (legacy 주석에만 남음).

---

## 7. Threading & Concurrency Model

| 영역 | 모델 | 비고 |
|---|---|---|
| HTTP requests | Spring MVC 기본 thread pool (Tomcat) | per-request 1 thread |
| `RestTemplate` (Ollama/Wikipedia) | blocking sync | 각 요청 spike시 Ollama가 직렬 처리 → latency 누적 |
| `ArticleParquetStore` | **race 가능** | `loadAll → mutate → saveAll` 패턴, 동시 ingest 시 lost-update 위험 |
| H2 / JPA | Spring TX 기본 | governance write는 single-row insert라 사실상 문제 없음 |

**현재는 단일 사용자 / 데모 부하 기준 설계**. 멀티 테넌시/동시성 강화는 roadmap 항목.

---

## 8. Boundaries & Anti-Corruption

- **WikipediaResponse** ↔ **Article**: Wikipedia API 스키마 (title/extract/content_urls.desktop.page) 를 그대로 도메인에 노출하지 않고 `IngestionService` 안에서 매핑. Wikipedia 응답 포맷이 바뀌어도 `Article` 은 안 흔들림.
- **OllamaRequest/Response** ↔ **AskRequest/RagResult**: 외부 LLM 응답 포맷이 컨트롤러/프론트 응답에 새지 않도록 `OllamaService.ask()` 가 String만 반환.
- **Article.embedding** `@JsonProperty(WRITE_ONLY)`: 외부 API 응답에는 절대 안 나감 (저장은 됨). storage detail 누출 차단.

---

## 9. Deployment Topology (현재)

```
┌──────────────────────────────────────────────────────┐
│ Local machine (laptop)                               │
│                                                      │
│  ┌────────────────┐    11434     ┌────────────────┐  │
│  │ Spring Boot    │◄────────────►│ ollama serve   │  │
│  │ :8080          │   HTTP/JSON  │ gemma4         │  │
│  │ MiniWatson     │              │ nomic-embed... │  │
│  └───┬────────┬───┘              └────────────────┘  │
│      │        │                                      │
│      │        └──► ./data/articles.parquet           │
│      │                                               │
│      └──► H2 (in-memory or ./data/miniwatson.mv.db)  │
│                                                      │
│            (Wikipedia: 외부 HTTPS)                    │
└──────────────────────────────────────────────────────┘
```

**의도된 단순함**: sovereignty (모든 게 로컬, API key 없음). 클라우드/컨테이너 배포는 미구현. `application-prod.yaml` 만 골격 존재.

---

## 10. 다음 엔지니어가 알아야 할 architectural debt

1. **Brute-force similarity search** — `RagService.ask()` 가 매 호출마다 articles 전체 로드 + 전체 cosine 계산. N이 작아 OK. N>10k면 HNSW/FAISS 필요.
2. **Parquet rewrite on every ingest** — `saveAll()` 는 전체 파일 재작성. append-only 또는 row group 분리 전략 필요시 [ERD.md](./ERD.md) 의 evolution 섹션 참고.
3. **In-memory H2 (dev profile)** — 재시작하면 governance log 다 날아감. demo/prod 프로파일 사용해야 영구 저장.
4. **No auth / no rate limiting** — 로컬 학습용. 외부 공개 시 반드시 추가.
5. **`OllamaService`/`EmbeddingService` 의 `RestTemplate` 은 `new RestTemplate()` 직접 생성** — Spring 권장은 `RestTemplateBuilder` bean. timeout/retry/connection pool 설정이 빠져 있음.

위 항목은 [DEBUGGING.md §5 Landmines](./DEBUGGING.md) 또는 [SDS.md §7 Known Tech Debt](./SDS.md) 에 더 자세함.
