# MiniWatson — Architecture

A small but production-shaped reference of IBM watsonx's three-pillar architecture (**data · ai · governance**), built end-to-end on a laptop.

---

## 1. Layered View

```
┌─────────────────────────────────────────────────────────────┐
│  Presentation                                                │
│  ── Static dashboard (IBM Carbon, plain CSS)                 │
│     /static/index.html  ·  app.js  ·  styles.css             │
└────────────────────────────┬─────────────────────────────────┘
                             │ fetch
┌────────────────────────────▼─────────────────────────────────┐
│  REST API  (Spring Boot 4 · Jackson 3.x)                     │
│  ── DataController · RagController · AuditController         │
└────────────────────────────┬─────────────────────────────────┘
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
┌───────▼─────────┐  ┌───────▼─────────┐  ┌──────▼──────────┐
│ Service Layer   │  │ Service Layer   │  │ Service Layer    │
│ IngestionService│  │ RagService      │  │ AuditService     │
│ OllamaService   │  │ OllamaService   │  │ (JPA)            │
└───────┬─────────┘  └───────┬─────────┘  └──────┬──────────┘
        │                    │                    │
┌───────▼──────────┬─────────▼────────┐  ┌──────▼──────────┐
│ ArticleParquet   │  Ollama (LLM)    │  │ H2 (in-memory)   │
│ Store            │  localhost:11434 │  │ AuditLogRepo     │
│ Parquet+SNAPPY   │  gemma4, nomic   │  │                  │
└──────────────────┴──────────────────┘  └──────────────────┘
```

---

## 2. Component Map

| Component            | Responsibility                                        | Maps to watsonx     |
|----------------------|-------------------------------------------------------|---------------------|
| `IngestionService`   | Wikipedia fetch → embedding → store                    | watsonx.data (ETL)  |
| `ArticleParquetStore`| Avro + Parquet columnar store, SNAPPY compression      | watsonx.data        |
| `OllamaService`      | Single client to Ollama (chat + embed)                 | watsonx.ai runtime  |
| `RagService`         | Cosine similarity → top-K → augmented prompt           | watsonx.ai          |
| `AuditService`       | Logs every RAG call to H2 (JPA)                        | watsonx.governance  |
| `AuditController`    | Read-only API over governance log                      | watsonx.governance  |

---

## 3. Request Flow — RAG `POST /api/rag/ask`

```
Client
  │
  │ POST {"question": "What is RAG?"}
  ▼
RagController
  │
  ▼
RagService.ask(question)
  │
  ├─► OllamaService.embed(question)             ──► Ollama /api/embeddings (nomic-embed-text)
  │                                                  → float[768]
  │
  ├─► ArticleParquetStore.loadAll()             ──► data/articles.parquet  (read)
  │                                                  → List<Article>
  │
  ├─► cosineSimilarity(q, a.embedding) for each
  │   sort desc, take top-K (default 3)
  │
  ├─► buildPrompt(question, topSources)         ──► system + context + question
  │
  ├─► OllamaService.chat(prompt, think=false)   ──► Ollama /api/chat (gemma4)
  │                                                  → answer
  │
  ├─► AuditService.log(question, answer, ...)   ──► H2 in-memory DB
  │
  ▼
{ answer, sources[], model, tookMs }
```

---

## 4. Request Flow — Ingestion `POST /api/data/ingest-batch`

```
Client
  │ POST {"topics": ["RAG", "Vector database", ...]}
  ▼
DataController
  │
  └─► for each topic:
        │
        ├─► IngestionService.ingest(title)
        │     │
        │     ├─► WikipediaClient.fetchSummary(title)
        │     │      GET https://en.wikipedia.org/api/rest_v1/page/summary/{title}
        │     │      Headers: User-Agent: MiniWatson/1.0 (mailto:kdea989@gmail.com)
        │     │
        │     ├─► OllamaService.embed(summary) → float[768]
        │     │
        │     └─► ArticleParquetStore.save(article)
        │            │
        │            ├─► loadAll() (current state)
        │            ├─► append new article (id = size+1)
        │            └─► saveAll() (rewrite Parquet — small dataset OK)
        │
        └─► aggregate to {ingested, failed, articles, errors}
```

> **Note**: 작은 데이터셋이라 매 save마다 full rewrite. 운영 규모에서는 append-only Parquet 또는 Delta/Iceberg로 전환 — 여기선 학습 목적이므로 단순화.

---

## 5. watsonx Mapping (DAP ↔ watsonx)

| MiniWatson layer        | DAP component             | watsonx counterpart           |
|-------------------------|---------------------------|-------------------------------|
| Wikipedia REST + ingest | Data Acquisition          | watsonx.data (ingest)         |
| Article + embedding     | Knowledge Lake            | watsonx.data (object store)   |
| Avro schema             | Schema Registry           | watsonx.data (Iceberg/Delta)  |
| Parquet + SNAPPY        | Columnar storage          | watsonx.data                  |
| Ollama (gemma4, nomic)  | Text Platform             | watsonx.ai (foundation model) |
| Cosine retrieval        | Retrieval                 | watsonx.ai (vector search)    |
| RAG prompt build        | Inference pipeline        | watsonx.ai                    |
| Audit log (H2)          | Decision Log              | watsonx.governance            |
| `@JsonIgnoreProperties` | Anti-corruption layer     | data sovereignty pattern      |

---

## 6. Profiles

`application-{profile}.yaml` via Spring profiles.

| Profile | Use case            | Storage     | LLM     | Notes                          |
|---------|---------------------|-------------|---------|--------------------------------|
| `dev`   | Local dev (default) | Parquet     | Ollama  | Hot-reload, verbose logs       |
| `demo`  | Live demo / IBM     | Parquet     | Ollama  | Pre-seeded ingest, INFO logs   |
| `prod`  | Reserved            | S3/Iceberg  | watsonx | Not implemented (placeholder)  |

Activate via:
```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

---

## 7. Non-Functional Notes

- **Compression**: ~7× over JSON (54 KB → 7.8 KB observed on first 6 articles).
- **Embedding dim**: 768 (`nomic-embed-text`).
- **Top-K default**: 3 (configurable via `rag.top-k` later).
- **LLM token budget**: `num_predict=500` with `think: false`.
- **Hadoop on Java 21**: `-Djava.security.manager=allow` in `pom.xml` (Spring Boot plugin jvmArguments).
- **Schema namespace**: Avro `namespace: "miniwatson.schema"` (intentionally non-Java to force GenericRecord).

---

## 8. What's Intentionally Simple

These are **scope choices**, not bugs:

| Simplification          | Production version would…                            |
|-------------------------|------------------------------------------------------|
| H2 in-memory audit log  | PostgreSQL or Cloudant; retention policy             |
| Full rewrite on save    | Append-only Parquet via Iceberg / Delta              |
| Anonymous userId        | OIDC / JWT (IBM ID, Watson IAM)                      |
| No auth on RAG          | Per-tenant API keys + rate limit                     |
| Cosine in JVM           | FAISS / pgvector / Milvus for >10k articles          |
| Single-node Ollama      | watsonx.ai or LLM proxy with HA                      |

These are mapped 1:1 in `docs/DATA-MODEL.md` and `docs/GOVERNANCE.md`.

---

## 9. Why This Architecture (for IBM)

1. **Three-pillar parity** — data · ai · governance, mirroring watsonx.
2. **Anti-corruption** — Wikipedia DTO은 internal Article로 격리 (`@JsonIgnoreProperties`).
3. **Auditable by default** — 모든 RAG 호출이 governance log에 남음.
4. **Sovereign by design** — 모든 컴포넌트 localhost, 외부 SaaS 의존성 0.
5. **Java 21 + Spring Boot 4** — 최신 LTS + 최신 Spring (Jackson 3.x).
6. **OpenJ9 narrative** — IBM Semeru JDK 권장 (`pom.xml` 주석에 기록).
