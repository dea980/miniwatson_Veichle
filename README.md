# MiniWatson

> A miniature watsonx-style platform — built end-to-end from scratch  
> Spring Boot · Ollama · Parquet · 768-dim embeddings · RAG (chunking + reranking) · multimodal (vision + OCR) · multi-tenant · PII governance

MiniWatson is a learning project that recreates IBM watsonx's 3-layer architecture
(data · ai · governance) at a small scale. The goal: understand how enterprise
GenAI platforms work by building one — not by reading about it.

---

## Architecture

Three layers, each mapping to a watsonx component:

```
┌─────────────────────────────────────────────────────────┐
│  Frontend (Plain HTML + JS · IBM Carbon style)          │
│  http://localhost:8080                                  │
└─────────────────────────┬───────────────────────────────┘
│ REST · JSON
┌─────────────────────────▼───────────────────────────────┐
│  Backend: Spring Boot 4 · Java 21 (IBM Semeru)          │
│                                                         │
│  ┌────────────────────────────────────────────────────┐ │
│  │  AI Layer (watsonx.ai analog)                      │ │
│  │  • Chat: multi-LLM, per-request (gemma/granite/..) │ │
│  │  • Embeddings: 768-dim (nomic / granite-embedding) │ │
│  │  • Vision: image Q&A (llava / granite-vision)      │ │
│  │  • OCR grounding: Tesseract (exact text/numbers)   │ │
│  │  • RAG: chunk → embed → hybrid search → rerank     │ │
│  │  • Hybrid: vector + BM25 keyword (RRF fusion)      │ │
│  │  • Reranking: none/llm/mmr/cross (pluggable)       │ │
│  └────────────────────────────────────────────────────┘ │
│                                                         │
│  ┌────────────────────────────────────────────────────┐ │
│  │  Data Layer (watsonx.data analog)                  │ │
│  │  • Ingest: Wikipedia / image (vision+OCR) / file   │ │
│  │  • Chunking: fixed / recursive / semantic          │ │
│  │  • Multi-tenant namespaces + dedup + CRUD          │ │
│  │  • Tiered: hot JSON → cold Parquet (compaction)    │ │
│  │  • Catalog: H2 doc metadata (catalog/data split)   │ │
│  │  • Parquet (Avro schema, SNAPPY) — 7× < JSON       │ │
│  └────────────────────────────────────────────────────┘ │
│                                                         │
│  ┌────────────────────────────────────────────────────┐ │
│  │  Governance Layer (watsonx.governance analog)      │ │
│  │  • Auto audit log every LLM call in H2             │ │
│  │  • Tracks model, latency, timestamp                │ │
│  │  • PII detection & redaction before persist        │ │
│  │  • Provenance: source chunks logged per answer     │ │
│  └────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

---

## Tech Stack

| Layer | Choice | Why |
|---|---|---|
| Language | Java 21 (IBM Semeru) | Lower memory footprint than HotSpot |
| Framework | Spring Boot 4.0 | Enterprise standard, fast bootstrap |
| LLM | Ollama (local) | Sovereign deployment, no API keys |
| Chat model | gemma4 (default) · multi-LLM | per-request model, whitelist-validated |
| Embedding model | nomic-embed-text / granite-embedding | 768-dim, runs locally |
| Vision model | llava / granite-vision | image Q&A + caption (multimodal) |
| OCR | Tesseract (CLI) | exact text/number extraction for grounding |
| Data format | Apache Parquet | Columnar + SNAPPY = 7× smaller than JSON |
| Schema | Avro | Schema-first, evolution-safe |
| Storage | Tiered (JSON hot → Parquet cold) | cheap appends + columnar compaction |
| Catalog | H2 document_catalog (mirror) | SQL-queryable KB metadata; catalog/data split |
| Retrieval | In-memory LSH vector index | sub-linear approximate kNN |
| Hybrid search | Vector + BM25 keyword, RRF fusion | lexical recall for exact tokens (IDs, codes) |
| Chunking | fixed / recursive / semantic (pluggable) | recursive default; balance quality vs cost |
| Reranking | none / llm / mmr / cross (pluggable) | two-stage: fetch top-N → rerank → top-K |
| Cross-encoder | DJL + PyTorch + BGE-reranker | dedicated reranker model (Linux/Apple Silicon) |
| Database | H2 (in-memory) | Zero config for governance audit |
| Build | Maven | pom.xml + spring-boot-maven-plugin |
| Frontend | Plain HTML + JS | No framework lock-in, instant load |

---

## Quick Start

### Prerequisites

```bash
# 1. Java 21 (IBM Semeru recommended)
java --version    # → openjdk 21+

# 2. Ollama
brew install ollama
ollama pull gemma4              # chat (default)
ollama pull nomic-embed-text   # 768-dim embeddings
ollama pull llava              # vision (multimodal Q&A / image ingest)

# 3. OCR (for image grounding — exact text/numbers)
brew install tesseract

# 4. Start Ollama server (separate terminal)
ollama serve
```

### Run

```bash
# Clone
git clone https://github.com/dea980/miniwatson.git
cd miniwatson

# Run Spring Boot
./mvnw spring-boot:run

# Open browser
open http://localhost:8080
```

### Try it

1. **Ingest** a Wikipedia article  
   → Type `Retrieval-augmented_generation`, click Ingest
2. **Ask** a question  
   → Type `What is RAG?`, click Submit
3. **Audit Trail** tab  
   → See every Q&A logged with model, latency, timestamp

---

## API

### Ingest Wikipedia article

```bash
curl -X POST "http://localhost:8080/api/data/ingest?title=Vector_database"
```

Returns the stored article (with `id`, `title`, `summary`, `url`, `ingestedAt`).
Embedding is generated and stored in Parquet but hidden from the response.

Optional `&namespace=acme` scopes the article to a tenant (default: `default`).

### Ask a RAG question

```bash
curl -X POST http://localhost:8080/api/rag/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "What is RAG?", "namespace": "default", "model": "gemma4"}'
```

`namespace` and `model` are optional. Returns the answer plus the top-K source
articles used for grounding.

### Multi-LLM — list selectable chat models

```bash
curl http://localhost:8080/api/rag/models      # { default, available[] }
```

### Multimodal — image Q&A and image ingest

```bash
# Ask about an image (vision + OCR grounding)
curl -X POST http://localhost:8080/api/multimodal/ask \
  -F "image=@invoice.png" -F "question=What is the total?"

# Ingest an image into the knowledge base (searchable by later text queries)
curl -X POST http://localhost:8080/api/multimodal/ingest \
  -F "image=@invoice.png" -F "namespace=demo"
```

### Upload a text/document file (any type via Apache Tika)

```bash
curl -X POST http://localhost:8080/api/data/ingest-file \
  -F "file=@report.pdf" -F "namespace=demo"
```

The file is text-extracted (Tika: PDF/docx/txt/csv/...), split into chunks, and
each chunk stored as an Article (`title #1`, `#2`, ...). Returns the list of
created chunks. Chunk strategy/size via `chunking.*` config.

### Summarize an uploaded document

```bash
curl -X POST http://localhost:8080/api/data/summarize/5   # any chunk id of the doc
```

Aggregates all chunks of the document (by base title) and returns a summary.
This is separate from RAG `ask` — summarization needs the whole document, not
retrieved fragments.

### List / delete articles, index stats

```bash
curl  http://localhost:8080/api/data/articles            # all (or ?namespace=demo)
curl -X DELETE http://localhost:8080/api/data/articles/5 # remove by id (+index resync)
curl  http://localhost:8080/api/data/index/stats         # LSH mode, vectors, buckets
```

### Documents (document-level view over chunks)

```bash
# List documents (chunks grouped by namespace + title)
curl "http://localhost:8080/api/data/documents"

# Delete a whole document (all its chunks at once)
curl -X DELETE "http://localhost:8080/api/data/documents?title=report.pdf&namespace=demo"
```

A long file is stored as many chunks; these endpoints present and manage it as one
document. The same metadata is mirrored to the H2 `document_catalog` for SQL queries.

### Audit trail & governance stats

```bash
curl http://localhost:8080/api/governance/logs    # every LLM call (model, latency, PII, sources)
curl http://localhost:8080/api/governance/stats   # aggregates: per-model, per-source-type, KPI totals
```

Every LLM call is logged: question, answer, model, latency (ms), timestamp, and
**PII count** (sensitive data is masked before persisting).


---

## Configuration

All settings are externalized via Spring profiles and environment variables.

```yaml
# application.yaml
spring:
profiles:
active: dev      # dev | demo | prod

ollama:
url: ${OLLAMA_URL:http://localhost:11434}
chat-model: ${OLLAMA_CHAT_MODEL:gemma4}
chat-models: ${OLLAMA_CHAT_MODELS:gemma4,ibm/granite4:latest,qwen3.6}  # multi-LLM whitelist
embed-model: ${OLLAMA_EMBED_MODEL:nomic-embed-text}
vision-model: ${OLLAMA_VISION_MODEL:llava:latest}                       # multimodal
num-predict: ${OLLAMA_NUM_PREDICT:500}

storage:
tier:
threshold: ${STORAGE_TIER_THRESHOLD:100}   # hot(JSON) count before compaction → Parquet

vector:
index:
lsh:
enabled: ${VECTOR_LSH_ENABLED:true}        # LSH on/off (false = brute-force)
hyperplanes: ${VECTOR_LSH_HYPERPLANES:16}

chunking:
strategy: recursive   # fixed | recursive | semantic
max-size: 1000        # chars per chunk

rerank:
strategy: llm         # none | llm | mmr | cross
```

### Profile overrides

| Profile | Storage | When |
|---|---|---|
| `dev` (default) | H2 in-memory | Fast iteration |
| `demo` | H2 file-backed | Persistent demos |
| `prod` | Externalized via env vars | Real deployment |

Switch model without code change:

```bash
OLLAMA_CHAT_MODEL=qwen3.6 ./mvnw spring-boot:run
```

---

## Project Structure

```
miniwatson/
├── src/main/java/com/miniwatson/
│   ├── MiniwatsonApplication.java
│   ├── controller/
│   │   ├── RagController.java            # POST /api/rag/ask · GET /api/rag/models
│   │   ├── DataController.java           # /api/data/* (ingest, file, delete, stats)
│   │   ├── MultimodalController.java     # /api/multimodal/ask · /ingest (vision)
│   │   └── GovernanceController.java     # GET /api/governance/logs
│   ├── service/
│   │   ├── OllamaService.java            # Chat (multi-LLM) + vision (images)
│   │   ├── EmbeddingService.java         # Embed: 768-dim
│   │   ├── OcrService.java               # Tesseract CLI → text
│   │   ├── IngestionService.java         # Wikipedia / image / file → chunk → Article
│   │   ├── IndexingService.java          # one place to update all indexes (vector + keyword)
│   │   ├── HybridRetriever.java          # vector + BM25 candidates, RRF fusion
│   │   ├── Chunker.java                  # interface: fixed / recursive / semantic
│   │   ├── FixedChunker.java             # N-char + overlap (baseline)
│   │   ├── RecursiveChunker.java         # separator-priority split (default)
│   │   ├── SemanticChunker.java          # sentence-embedding boundary detection
│   │   ├── Reranker.java                 # interface: none / llm / mmr / cross
│   │   ├── NoopReranker.java             # 1st-stage top-K passthrough (baseline)
│   │   ├── LlmReranker.java              # listwise LLM rerank
│   │   ├── MmrReranker.java              # relevance + diversity (MMR)
│   │   ├── CrossEncoderReranker.java     # DJL cross-encoder (graceful fallback)
│   │   └── RagService.java               # Embed → LSH search (top-N) → rerank → top-K
│   ├── data/
│   │   ├── Article.java                  # POJO + namespace + embedding (write-only)
│   │   ├── WikipediaResponse.java        # External API DTO
│   │   ├── ArticleRepository.java        # storage interface
│   │   ├── ArticleStore.java             # JSON store (hot tier)
│   │   ├── ArticleParquetStore.java      # Parquet store (cold tier)
│   │   ├── TieredArticleStore.java       # hot→cold compaction (@Primary)
│   │   ├── VectorIndex.java              # in-memory LSH index (semantic)
│   │   └── KeywordIndex.java             # in-memory BM25 index (lexical)
│   ├── governance/
│   │   ├── QueryLog.java                 # JPA entity (+ piiCount, sources/provenance)
│   │   ├── QueryLogRepository.java       # Spring Data JPA
│   │   ├── DocumentCatalog.java          # KB metadata mirror (H2, catalog/data split)
│   │   ├── DocumentCatalogRepository.java
│   │   └── PiiRedactionService.java      # regex PII masking
│   └── dto/
│       ├── AskRequest.java
│       ├── OllamaRequest.java            # Includes think:false
│       ├── OllamaResponse.java
│       ├── EmbeddingRequest.java
│       └── EmbeddingResponse.java
├── src/main/resources/
│   ├── application.yaml                  # Common config + active profile
│   ├── application-dev.yaml              # H2 in-memory
│   ├── application-demo.yaml             # H2 file-backed
│   ├── application-prod.yaml             # Env vars (PostgreSQL ready)
│   ├── article.avsc                      # Avro schema for Parquet
│   └── static/
│       ├── index.html                    # Dashboard
│       ├── css/styles.css                # IBM Carbon-inspired
│       └── js/app.js                     # fetch + DOM
├── data/                                 # runtime state (gitignored)
│   ├── articles.json                     # hot tier (recent appends)
│   └── articles.parquet                  # cold tier (compacted)
├── docs/                                 # API, ARCHITECTURE, GOVERNANCE, MULTIMODAL, ...
├── sample/                               # demo fixtures (invoice, chart, text)
└── pom.xml
```

---

## Storage Efficiency

Migrating embedding storage from JSON to Parquet:

| Format | Size | Compression |
|---|---|---|
| JSON (with 768-dim float arrays) | 54 KB | baseline |
| **Parquet (SNAPPY)** | **7.8 KB** | **7×** |

Parquet's columnar layout means embedding columns compress aggressively
while still allowing per-row reads. This is exactly why watsonx.data uses
Parquet as its native format.

---

## What I Learned

Notes from building this:

- **Java 21 + Hadoop SecurityManager** — Hadoop's `UserGroupInformation`
  calls `Subject.getSubject()` which Java 17+ deprecated. Fix:
  `-Djava.security.manager=allow` in JVM arguments.

- **Gemma "thinking" tokens** — gemma3/gemma4 uses internal reasoning
  tokens that drain the `num_predict` budget. `think: false` disables
  this; cut latency by ~3×.

- **Wikipedia User-Agent policy** — REST API requires
  `User-Agent: AppName/version (URL; email)` or returns 403. Standard
  enterprise API hygiene.

- **Anti-corruption layer** — Keeping `WikipediaResponse` separate from
  internal `Article` lets the external API change without touching the
  rest of the codebase.

- **`@JsonProperty(WRITE_ONLY)`** — Hide 768-dim embedding from the API
  response while keeping it in storage. Trims 50KB+ off every response.

- **Spring profiles for sovereignty** — dev/demo/prod with environment
  variables means the same code ships to different environments with
  different credentials. This is the technical implementation of
  "sovereignty at the core."

- **Vision models hallucinate numbers; OCR doesn't** — `llava` invented an
  invoice total that wasn't on the page. The fix wasn't a bigger model — it was
  splitting roles: OCR (Tesseract) for exact text, vision for layout/context,
  and a prompt that tells the LLM to *trust OCR over vision* on conflicts.
  Combining sources isn't enough; you must declare which one is authoritative.
  (See `docs/MULTIMODAL.md` for the full before/after and limitations.)

- **OCR has its own failure modes** — it nails row-structured tables but
  mangles low-contrast/inverted text and loses the 2-D mapping in charts
  (reads `$28M` but not that it belongs to Q4). The hard part is the pipeline,
  not the model.

- **LSH for sub-linear retrieval** — random-hyperplane signatures bucket
  similar vectors so a query only scores a small candidate set, with an
  exact-cosine fallback for correctness. Dimension-agnostic (384/768/1024).

- **Chunking is the real fix for long-doc retrieval** — a 90k-char PDF stored
  as one embedding broke retrieval: the embedder truncates past ~8k tokens, and
  one vector can't match a specific passage. Splitting into per-chunk Articles
  fixed it (101 chunks). Compared fixed/recursive/semantic — recursive wins on
  quality-vs-cost; semantic is best but pays a per-sentence embedding cost.
  (See `docs/CHUNKING.md`.)

- **Reranking helps most when first-stage search is weak** — fetch top-N (20)
  then rerank to top-K (2). On a strong embedder + good chunks, easy questions
  already rank right and rerank barely changes them; the gain shows on
  vocabulary-mismatch questions. Built none/llm/mmr/cross to compare.
  (See `docs/RERANKING.md`.)

- **Hybrid search fixes vector's blind spot for exact tokens** — embeddings can't
  match "INV-2026-0042" or a model name; BM25 (lexical) can. Fused vector + BM25
  with RRF (rank-based, no score normalization). Same caveat as rerank: on a small
  clean corpus the win is small (top-N already covers everything); it pays off on
  large/noisy corpora with rare-token queries. Indexing was split into one
  `IndexingService` so adding the keyword index touched only that one class.
  (See `docs/HYBRID-SEARCH.md`.)

- **Pin the error to the real cause, then design a fallback** — the DJL
  cross-encoder failed to load on Intel macOS. Suspected the OpenJ9 (Semeru)
  JVM first, but switching to HotSpot reproduced it — the real cause was a
  missing osx-x86_64 native (PyTorch dropped Intel-mac wheels). The reranker
  falls back to top-K instead of crashing (graceful degradation); it runs on
  Linux/Apple Silicon. Library APIs also differ by version — confirmed the
  0.30.0 javadoc instead of trusting an example (no `CrossEncoderTranslatorFactory`;
  input is `StringPair`).

- **Tiered storage = lakehouse in miniature** — cheap row-oriented appends
  (JSON hot tier) compacted into columnar Parquet (cold tier) past a threshold.
  Avoids rewriting the whole Parquet file on every single ingest.

- **Governance must redact PII** — the audit log is the leak risk. Mask
  emails/phones/SSNs/cards *before persisting*, return the original to the user.
  Function preserved, record protected.

- **Provenance makes answers auditable** — logging the rerank-final source chunks
  per answer means you can later check "was this grounded, and in what?" — and tell
  a retrieval error (wrong chunk) apart from a generation error (right chunk, wrong
  answer). One subtle bug: set the field *before* `save()`, or it never persists.

- **Catalog/data split = lakehouse in miniature** — vectors and text live in
  Parquet (the data); lightweight document metadata is mirrored to H2 (the catalog),
  so the knowledge base itself becomes SQL-queryable for governance. Parquet is the
  source of truth; the H2 catalog is rebuilt from it on startup (`@PostConstruct`),
  same philosophy as the vector index hydrate.

- **Spring Boot 4 ignores `javax.annotation`** — `@PostConstruct` silently never
  ran because it was imported from `javax`, not `jakarta`. On Jakarta EE, callbacks
  must use `jakarta.annotation`. When a lifecycle hook quietly doesn't fire, suspect
  the javax/jakarta namespace first.

---

## Roadmap

- [x] 1 — Spring Boot setup
- [x] 2 — Ollama integration (watsonx.ai analog)
- [x] 3 — H2 audit log (watsonx.governance analog)
- [x] 4 — Wikipedia → Parquet (watsonx.data analog)
- [x] 5 — RAG with embeddings + cosine similarity
- [x] 6 — Frontend dashboard (IBM Carbon-style)
- [x] 7 — Multi-tenant article namespacing
- [x] 8 — Vector index (random-hyperplane LSH) for sub-linear retrieval
- [x] 9 — Multi-LLM chat model selection (per-request, whitelist)
- [x] 10 — Multimodal vision Q&A + image ingest (Ollama vision)
- [x] 11 — OCR grounding (Tesseract) + OCR/Vision fusion
- [x] 12 — PII detection & redaction in audit log (governance)
- [x] 13 — Tiered storage (hot JSON → cold Parquet compaction)
- [x] 14 — Knowledge-base CRUD (delete, dedup, file upload)
- [x] 15 — Universal file ingest (Apache Tika) + document chunking (fixed/recursive/semantic)
- [x] 16 — Two-stage retrieval with pluggable reranking (none/llm/mmr/cross)
- [x] 17 — Provenance: source chunks logged per answer (governance)
- [x] 18 — Document catalog in H2 (catalog/data split, SQL-queryable KB)
- [x] 19 — Governance stats dashboard (per-model, per-source-type, KPIs)
- [x] 20 — Hybrid search (vector + BM25, RRF) with indexing split
- [ ] deployment notes (Docker + compose) — also verifies cross-encoder on Linux
- [ ] tenant isolation enforcement / API auth

---

## Why This Project

Reading about watsonx is one thing. Recreating its data-ai-governance
loop end-to-end is another. The point was to find out where the hard
parts actually are.

**Verdict:** they aren't in the model. They're in the pipeline,
the storage format, and the audit trail. That matches IBV CEO Study's
"6 capabilities for 5.4× adoption" — change management, AI governance,
data governance, real-time integration, system integration, financial
integration. Model selection is the easy part.

---

## License

MIT

---

## Documentation

| Doc | What's inside |
|---|---|
| [docs/API.md](docs/API.md) | REST API reference — every endpoint with curl + schemas |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Component diagram, request flows, watsonx mapping |
| [docs/DATA-MODEL.md](docs/DATA-MODEL.md) | Article schema, Avro + Parquet, anti-corruption layer |
| [docs/GOVERNANCE.md](docs/GOVERNANCE.md) | Audit log schema + PII redaction, watsonx.governance parity |
| [docs/MULTIMODAL.md](docs/MULTIMODAL.md) | Vision Q&A + OCR grounding, image ingest, findings & limits |
| [docs/CHUNKING.md](docs/CHUNKING.md) | Chunking strategies (fixed/recursive/semantic), measured comparison |
| [docs/CHUNKING-TEST.md](docs/CHUNKING-TEST.md) | Step-by-step guide to reproduce the chunking comparison |
| [docs/RERANKING.md](docs/RERANKING.md) | Two-stage retrieval, reranker strategies, before/after + platform findings |
| [docs/HYBRID-SEARCH.md](docs/HYBRID-SEARCH.md) | Vector + BM25 hybrid retrieval, RRF fusion, indexing split, measured limits |
| [docs/H2-CONSOLE.md](docs/H2-CONSOLE.md) | H2 web console — enable, login, SQL cookbook, prod warning |

**Live (interactive) API docs**: run the app, then open [`http://localhost:8080/swagger-ui.html`](http://localhost:8080/swagger-ui.html). See [`SWAGGER-SETUP.md`](SWAGGER-SETUP.md) to enable.

---

## Author

**Daeyeop Kim** — [github.com/dea980](https://github.com/dea980) · kdea989@gmail.com
