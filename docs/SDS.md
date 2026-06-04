# MiniWatson — Software Design Specification (SDS)

> 패키지별 · 클래스별 명세. 코드를 수정하기 전에 해당 섹션을 먼저 확인하세요.

---

## 0. Package Map

| Package | 책임 | 파일 |
|---|---|---|
| `com.miniwatson` | Spring Boot 부트스트랩 | `MiniwatsonApplication.java` |
| `com.miniwatson.controller` | HTTP presentation | `HelloController`, `AskController`, `RagController`, `DataController`, `GovernanaceController` |
| `com.miniwatson.service` | 비즈니스 로직 (AI + ingestion + RAG) | `OllamaService`, `EmbeddingService`, `IngestionService`, `RagService` |
| `com.miniwatson.data` | 도메인 + 영속화 | `Article`, `WikipediaResponse`, `ArticleParquetStore`, `ArticleStore` (legacy) |
| `com.miniwatson.governance` | LLM 호출 audit | `QueryLog`, `QueryLogRepository` |
| `com.miniwatson.dto` | 외부 API 요청/응답 모델 | `AskRequest`, `OllamaRequest`, `OllamaResponse`, `EmbeddingRequest`, `EmbeddingResponse` |

---

## 1. Controllers

### 1.1 HelloController
- **URL**: `GET /api/hello`, `GET /api/version`
- **책임**: liveness / 버전 표시. 외부 의존 없음.
- **수정 시 유의점**: dashboard `app.js` 가 부팅 직후 `/api/hello` 를 한 번 호출하므로 응답 텍스트 바꾸면 프론트 동기화 필요.

### 1.2 AskController
- **URL**: `POST /api/ask`
- **request body**: `AskRequest { question }`
- **response**: `String` (LLM 답변 그대로)
- **흐름**: `ask() → ollamaService.ask(question)`
- **특이사항**: RAG 컨텍스트 **없이** 그냥 LLM에 패스. 비교용/디버깅용. governance log는 OllamaService 안에서 자동 기록.

### 1.3 RagController
- **URL**: `POST /api/rag/ask`
- **request body**: `AskRequest { question }`
- **response**: `RagService.RagResult { answer, sources[] }`
- **흐름**: `ragService.ask(question)` 위임.
- **에러**: knowledge base가 비면 `RuntimeException("No articles in knowledge base.")` 그대로 500 응답.

### 1.4 DataController
| Endpoint | Method | Body / Param | 동작 |
|---|---|---|---|
| `/api/data/ingest` | POST | `?title=` | 단건 ingest |
| `/api/data/ingest-batch` | POST | `{ "topics": [...] }` | 다건 ingest, 실패도 함께 반환 |
| `/api/data/articles` | GET | - | Parquet 전체 로드 → JSON |
| `/api/data/count` | GET | - | `{ "count": N }` (대시보드용) |

- **batch ingest의 응답 스키마**:
  ```json
  { "success": true, "ingested": N, "failed": M,
    "articles": [...], "errors": [{"title":"X","error":"..."}, ...] }
  ```
- **embedding 필드**: `@JsonProperty(WRITE_ONLY)` 로 응답에서 자동 제거.

### 1.5 GovernanaceController
>  클래스명 오타 (`Governanace` → `Governance`). 다른 코드는 이걸 사용하지 않으므로 안전하게 rename 가능하지만 history 보존을 원한다면 그대로 둘 것.
- **URL**: `GET /api/governance/logs`
- **응답**: `List<QueryLog>` (JSON, 최근순 정렬 없음 — `JpaRepository.findAll()` 기본 순서).
- **확장 후보**: 페이지네이션, 기간 필터, 모델별 그룹핑. 현재는 raw dump.

---

## 2. Services

### 2.1 OllamaService
**역할**: chat 모델 호출 + **governance audit log 기록**.

```java
@Service
public class OllamaService {
    @Value("${ollama.url}")        String  ollamaUrl;
    @Value("${ollama.chat-model}") String  model;
    @Value("${ollama.num-predict}") int    numPredict;
    private final RestTemplate restTemplate = new RestTemplate();
    private final QueryLogRepository queryLogRepository;   // ← governance hook
    public String ask(String question) { ... }
}
```

**`ask(String)` sequence**:
1. `startTime = currentTimeMillis()`
2. `OllamaRequest` 조립:
   - `model = ${ollama.chat-model}`
   - `stream = false`
   - **`think = false`** — gemma reasoning tokens 비활성 (⚠️ 변경 금지, [DEBUGGING.md](./DEBUGGING.md) 참조)
   - `options = { "num_predict": ${ollama.num-predict} }`
3. `restTemplate.postForObject(ollamaUrl + "/api/generate", request, OllamaResponse.class)`
4. `latency = now - startTime`
5. `QueryLog{ question, answer, model, latencyMs }` save → H2.
6. return `response.getResponse()` (null이면 `"Error: no response"`).

**의도된 design choice**:
- audit log는 서비스 안에 있어야 한다 (controller 안이 아님) → ingest, direct ask, RAG 어디서 부르든 누락 안 됨.
- timestamp는 `QueryLog.@PrePersist` 에서 채워짐.

**알려진 한계**:
- `RestTemplate` 에 timeout 미설정 → Ollama 멈추면 무한 대기 가능.
- governance 저장 실패 시 답변도 함께 실패 (트랜잭션 분리 X) — 학습 프로젝트에서는 의도된 단순함.

### 2.2 EmbeddingService
**역할**: text → 768-dim float vector.

```java
public List<Float> embed(String text) {
    EmbeddingRequest req = new EmbeddingRequest();
    req.setModel(embedModel);          // nomic-embed-text
    req.setInput(text);
    EmbeddingResponse resp = restTemplate.postForObject(
        ollamaUrl + "/api/embed", req, EmbeddingResponse.class);
    return resp.getEmbeddings().get(0); // 2D → 1D
}
```

**왜 `List<List<Float>>` 인가?**: Ollama `/api/embed` 는 batch input을 받을 수 있어 응답이 2D. 우리는 항상 single input → 첫 번째만 사용.

**실패 처리**: null/empty면 `RuntimeException("No embedding returned for text: …")`.

### 2.3 IngestionService
**역할**: Wikipedia → 정규화된 `Article` → embedding → store.

핵심 코드:
```java
String url = "https://en.wikipedia.org/api/rest_v1/page/summary/" + title;
HttpHeaders headers = new HttpHeaders();
headers.set("User-Agent",
   "MiniWatson/1.0 (https://github.com/dea980/miniwatson; kdea989@gmail.com)");
// ⚠️ User-Agent 없이 호출하면 Wikipedia가 403 반환
ResponseEntity<WikipediaResponse> r = restTemplate.exchange(url, GET, request, WikipediaResponse.class);

Article a = new Article();
a.setTitle(r.getBody().getTitle());
a.setSummary(r.getBody().getExtract());
a.setUrl(r.getBody().getContent_urls().getDesktop().getPage());
a.setIngestedAt(LocalDateTime.now());
a.setEmbedding(embeddingService.embed(a.getTitle() + ". " + a.getSummary()));
return articleStore.save(a);  // ArticleParquetStore
```

**embedding 입력 텍스트**: `"{title}. {summary}"` 으로 합쳐서 임베딩. 추후 변경 시 (e.g. summary만 사용) 기존 articles는 일관성이 깨짐 → 전체 재임베딩이 필요.

### 2.4 RagService
**역할**: retrieval (cosine top-K) + augmentation + LLM 호출.

```java
private static final int TOP_K = 2;

public RagResult ask(String question) throws IOException {
    List<Float> qv = embeddingService.embed(question);
    List<Article> all = articleStore.loadAll();
    if (all.isEmpty()) throw new RuntimeException("No articles in knowledge base.");

    List<Article> top = all.stream()
        .filter(a -> a.getEmbedding() != null && !a.getEmbedding().isEmpty())
        .sorted(Comparator.comparingDouble(a -> -cosine(qv, a.getEmbedding())))
        .limit(TOP_K)
        .toList();

    StringBuilder ctx = new StringBuilder();
    for (Article a : top) ctx.append("- ").append(a.getTitle())
                            .append(": ").append(a.getSummary()).append("\n");

    String prompt = "Based on this context:\n" + ctx
                  + "\nAnswer the question concisely: " + question;

    String answer = ollamaService.ask(prompt);  // governance log 자동
    return new RagResult(answer, top);
}
```

**튜닝 포인트**:
- `TOP_K`: 2. 너무 크면 prompt 길이 폭증 → gemma `num_predict` 도달 전에 컨텍스트 소진.
- prompt template: 단순 markdown bullet. 모델 교체 시 (qwen, llama 등) prompt 형식 재실험 필요.
- cosine similarity는 brute-force `O(N · d)` — Article 개수가 늘면 vector index 도입 검토.

**`RagResult`**: Java `record(String answer, List<Article> sources)`. Jackson이 자동 직렬화 → `sources[*].embedding` 은 `@JsonProperty(WRITE_ONLY)` 로 숨김.

---

## 3. Sequence Diagrams

### 3.1 Ingest
```
Client       DataController     IngestionService    EmbeddingService    ArticleParquetStore   Wikipedia   Ollama
  │ POST ingest?title=X │              │                  │                    │              │           │
  │─────────────────────►│             │                  │                    │              │           │
  │                     │  ingest(X)   │                  │                    │              │           │
  │                     │─────────────►│                  │                    │              │           │
  │                     │              │ GET summary/X    │                    │              │           │
  │                     │              │  (User-Agent)    │                    │              │           │
  │                     │              │──────────────────┼────────────────────┼─────────────►│           │
  │                     │              │◄─────────────────┼────────────────────┼──────────────│           │
  │                     │              │  WikipediaResp.  │                    │              │           │
  │                     │              │  embed(text)     │                    │              │           │
  │                     │              │─────────────────►│                    │              │           │
  │                     │              │                  │  POST /api/embed   │              │           │
  │                     │              │                  │────────────────────┼──────────────┼──────────►│
  │                     │              │                  │◄───────────────────┼──────────────┼────────── │
  │                     │              │◄─ List<Float>(768)                    │              │           │
  │                     │              │  save(article)   │                    │              │           │
  │                     │              │─────────────────────────────────────► │              │           │
  │                     │              │                  │  loadAll + write parquet          │           │
  │                     │              │◄─ Article(id set)│                    │              │           │
  │                     │◄─ Article    │                  │                    │              │           │
  │◄─ Article JSON ─────│              │                  │                    │              │           │
```

### 3.2 RAG Q&A
```
Client      RagController   RagService   EmbeddingService   ArticleParquetStore   OllamaService   H2 (governance)   Ollama
  │ POST /rag/ask │             │             │                    │                   │                │            │
  │──────────────►│             │             │                    │                   │                │            │
  │               │ ask(q)      │             │                    │                   │                │            │
  │               │────────────►│             │                    │                   │                │            │
  │               │             │ embed(q)    │                    │                   │                │            │
  │               │             │────────────►│ POST /api/embed    │                   │                │            │
  │               │             │             │────────────────────┼───────────────────┼────────────────┼───────────►│
  │               │             │             │◄ List<Float>(768) ─┼───────────────────┼────────────────┼────────────│
  │               │             │ loadAll()   │                    │                   │                │            │
  │               │             │────────────────────────────────► │                   │                │            │
  │               │             │◄─ List<Article> ─────────────────│                   │                │            │
  │               │             │ cosineSim + limit(TOP_K=2)       │                   │                │            │
  │               │             │ build augmented prompt           │                   │                │            │
  │               │             │ ask(prompt)                      │                   │                │            │
  │               │             │─────────────────────────────────────────────────────►│                │            │
  │               │             │                                  │                   │ POST /api/generate          │
  │               │             │                                  │                   │────────────────┼───────────►│
  │               │             │                                  │                   │◄─ answer ──────┼────────────│
  │               │             │                                  │                   │ save QueryLog  │            │
  │               │             │                                  │                   │───────────────►│            │
  │               │             │◄ answer ─────────────────────────────────────────────│                │            │
  │               │◄─ RagResult                                                        │                │            │
  │◄─ JSON ───────│                                                                                                  │
```

---

## 4. Data Layer — Detailed

### 4.1 `Article` (POJO)
```java
@Data
public class Article {
    private long          id;
    private String        title;
    private String        summary;
    private String        url;
    private LocalDateTime ingestedAt;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private List<Float>   embedding;   // 768 dims
}
```

- `@Data` (Lombok): getter/setter/equals/hashCode/toString 자동.
- **`embedding` 의 `WRITE_ONLY`**: Jackson 직렬화 시 **응답에서 제외**, 역직렬화 시 **요청에서 허용**. 클라이언트가 embedding 채워서 ingest하는 미래 시나리오 대비 + 응답 50KB+ 절약.

### 4.2 `WikipediaResponse` (외부 DTO, anti-corruption)
```java
@Data @JsonIgnoreProperties(ignoreUnknown = true)
public class WikipediaResponse {
    private String title;
    private String extract;
    private ContentUrls content_urls;          // snake_case 그대로

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContentUrls { private Desktop desktop; }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Desktop     { private String page; }
}
```
- **inner class는 반드시 `static`** — non-static이면 Jackson이 outer instance 없이 만들 수 없어 deserialize 실패.
- 새 필드 (description, thumbnail, …) 가 필요해지면 그대로 필드 추가하면 됨 (`ignoreUnknown=true`).

### 4.3 `ArticleParquetStore`
**경로**: `./data/articles.parquet` (working directory 기준).

**Schema** (`src/main/resources/article.avsc`):
```json
{
  "type": "record",
  "name": "Article",
  "namespace": "miniwatson.schema",
  "fields": [
    {"name":"id",         "type":"long"},
    {"name":"title",      "type":"string"},
    {"name":"summary",    "type":"string"},
    {"name":"url",        "type":"string"},
    {"name":"ingestedAt", "type":"string"},
    {"name":"embedding",  "type":{"type":"array","items":"float"}}
  ]
}
```

**메서드**:

| 메서드 | 동작 | 시간복잡도 |
|---|---|---|
| `saveAll(List<Article>)` | 기존 파일 삭제 → SNAPPY로 재작성 | O(N·d) |
| `loadAll()` | sequential read → List<Article> | O(N·d) |
| `save(Article)` | `loadAll → set id (size+1) → append → saveAll` | O(N·d) |

**주의**:
- Parquet은 **in-place mutation 불가**. `save()` 1건도 전체 재작성. 데이터 수십~수백 건까지는 OK.
- `LocalDateTime` 은 Avro에 native가 없어 `String` (`.toString()` ISO-8601) 으로 보관. `loadAll()` 에서 `LocalDateTime.parse()` 로 역변환.
- Reader는 `withDataModel()` **호출 금지** (Parquet 1.14 API에서 NPE 유발 — 이미 빠져 있음).

### 4.4 `ArticleStore` (legacy)
JSON 버전. 더 이상 어디서도 주입되지 않음. 안전하게 삭제 가능. 단:
1. `@Component` 라서 Spring context에 빈으로 남아 있음 → 메모리 차지는 미미.
2. 학습 history로 의도적으로 둠.

제거 절차 (필요시):
1. `data/articles.json.backup` 도 같이 정리.
2. `ArticleStore.java` 삭제.
3. 컴파일 통과 확인 (어디서도 import 안 됨).
4. README의 "JSON vs Parquet" 비교 표는 그대로 유지 가능 (역사적 의미).

---

## 5. Governance Layer

### 5.1 `QueryLog` (JPA Entity)
```java
@Entity @Data @Table(name = "query_log")
public class QueryLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(columnDefinition = "TEXT") private String question;
    @Column(columnDefinition = "TEXT") private String answer;
    private String        model;
    private Long          latencyMs;
    private LocalDateTime createdAt;

    @PrePersist void onCreate() { this.createdAt = LocalDateTime.now(); }
}
```

- `IDENTITY` 전략: H2/PostgreSQL 모두 호환.
- `TEXT` 컬럼 def: H2에서 LOB로 매핑되어 긴 prompt/answer 보관 가능.
- `@PrePersist`: createdAt을 entity가 직접 채움 → JPA flush 시점.

### 5.2 `QueryLogRepository`
```java
public interface QueryLogRepository extends JpaRepository<QueryLog, Long> { }
```
기본 메서드만 사용 (`save`, `findAll`). 확장 시 `findByModelOrderByCreatedAtDesc(...)` 같은 derived query를 추가하면 됨.

### 5.3 Audit hook 위치
**`OllamaService.ask()` 내부에만 존재**. 즉:
- `/api/ask`, `/api/rag/ask` → audit O
- `/api/data/ingest` → audit X (embedding-only, 의도된 분리)
- 향후 다른 LLM 서비스를 만들면 같은 패턴으로 hook 박아야 함. AOP 분리는 미적용.

---

## 6. DTOs

| DTO | 사용처 | 필드 | 비고 |
|---|---|---|---|
| `AskRequest` | inbound (Ask/Rag) | `question` | |
| `OllamaRequest` | outbound → Ollama | `model`, `prompt`, `stream`, `options`, `think` | `think` 은 gemma 전용; null이면 모델 기본값 |
| `OllamaResponse` | inbound ← Ollama | `model`, `createdAt`, `response`, `done` | 나머지 필드는 주석으로 기록 (필요 시 활성화) |
| `EmbeddingRequest` | outbound → Ollama | `model`, `input` | |
| `EmbeddingResponse` | inbound ← Ollama | `model`, `embeddings: List<List<Float>>` | 2D — batch 가능성 대비 |

JSON key는 모두 camelCase. Wikipedia만 snake_case (`content_urls`) 그대로 둠.

---

## 7. Known Tech Debt (구체적 위치 + 권장 조치)

| # | 위치 | 문제 | 권장 조치 |
|---|---|---|---|
| TD-1 | `RagService.java:cosineSimilarity` | 매번 O(N·d) brute-force | N>1000 되면 HNSW (e.g. `lucene-core` Lucene99HnswVectorsFormat) 도입 |
| TD-2 | `ArticleParquetStore.save` | 1건 추가에 전체 rewrite | row group split or append-mode Parquet writer |
| TD-3 | `OllamaService.RestTemplate` | timeout/retry 없음 | `RestTemplateBuilder.setConnectTimeout/.setReadTimeout` |
| TD-4 | `IngestionService` (동시성) | `loadAll → mutate → saveAll` race | synchronization or single-writer queue |
| TD-5 | `GovernanaceController` (오타) | class name `Governanace` | rename to `GovernanceController` (호환성 영향 없음, URL 그대로) |
| TD-6 | `ArticleStore` (legacy) | 빈으로 남아 dead code | 제거 (§4.4 절차) |
| TD-7 | `application-prod.yaml` | 미검증, datasource만 골격 | PostgreSQL driver 추가 + Flyway/Liquibase 도입 |
| TD-8 | governance hook | `OllamaService` 안에 묻혀 있음 | Spring AOP `@AfterReturning` 으로 분리하면 audit 누락 위험 더 줄어듦 |

---

## 8. 새 기능 추가 패턴 (cookbook)

### 8.1 새 REST endpoint 추가
1. `controller/` 에 새 컨트롤러 또는 기존 컨트롤러에 메서드 추가.
2. 비즈니스 로직은 반드시 `service/` 의 새 서비스/메서드로.
3. 요청/응답 모델은 `dto/` (외부 노출용) 또는 `data/` (도메인) 중 선택. 외부 API DTO ≠ 도메인 모델 분리 원칙 지킬 것.
4. `static/js/app.js` 에서 fetch 호출 추가 (대시보드 확장 시).
5. [API.md](./API.md) 업데이트.

### 8.2 새 LLM 모델 교체
```bash
ollama pull qwen3.6
OLLAMA_CHAT_MODEL=qwen3.6 ./mvnw spring-boot:run
```
- `think:false` 는 gemma 전용 옵션 — 다른 모델에 보내도 무시되긴 하나, 모델별 검증 권장.
- prompt 형식이 모델마다 다를 수 있음 (`RagService` 의 template 재실험).

### 8.3 Parquet schema 진화
[ERD.md §5 Schema Evolution](./ERD.md#5-schema-evolution-plan) 참조.

### 8.4 governance log에 새 필드 추가
1. `QueryLog` 에 필드 추가 + `@Column`.
2. `dev` 프로파일은 `create-drop` 이라 자동 반영. `demo`/`prod` 는 ddl-auto 정책에 따라 migration 필요.
3. `OllamaService.ask()` 에서 set.
