# 🚗 MiniWatson Vehicle

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](#license)

> **자동차 도메인 특화 LLM 플랫폼** — 정비 매뉴얼 RAG · 리콜/불만 text-to-SQL · 온디바이스 LoRA 파인튜닝 · Agentic 진단→부품, 그리고 거버넌스까지 한 곳에서.
> Spring Boot · Ollama · Next.js · DuckDB · MLX(LoRA) · Qwen2.5 / IBM Granite

> watsonx 스타일 RAG 플랫폼([MiniWatson](https://github.com/dea980/miniwatson))을 **자동차 밸류체인(판매·제조·A·S)** 으로 특화한 프로젝트. 현대차 NLP/LLM 직무 JD + 실제 AI 스택(H-Chat 거버넌스 게이트웨이 · 자체 도메인 LLM · 차량 온디바이스)에 매핑.

**핵심 기능**
- **매뉴얼 RAG** — 정비 매뉴얼 근거로 한국어 답변(+출처). 다국어 임베딩으로 한↔영 교차검색.
- **text-to-SQL** — 리콜·불만(NHTSA)·부품 CSV를 자연어로 질의(DuckDB), 차트 시각화.
- **LoRA 파인튜닝** — Qwen2.5-1.5B를 자동차 도메인으로(MLX, GPU 없는 맥) → GGUF Q4 온디바이스.
- **Agentic Search** — 질문→도구선택(RAG/SQL/복합)→종합 + 트레이스, SQL 자기수정.
- **종합 진단서 / 이미지 진단→부품** — 리콜·불만·매뉴얼 통합 리포트, 사진(Vision+OCR) 진단.
- **거버넌스** — 모든 LLM 호출 감사 로그 · PII 마스킹 · 멀티프로바이더(H-Chat 정합).

전체 문서: **[docs/](docs/README.md)** · 설계 근거: [아키텍처](docs/VEHICLE_ARCHITECTURE.md) · [결과](docs/RESULTS.md) · 실행: [ml/RUNBOOK.md](ml/RUNBOOK.md)

## 프로젝트 구조

3개 계층이 HTTP/모델파일로만 연결된 모노레포 (서빙·프론트·ML 분리):

```
miniwatson-vehicle/
├── src/ · pom.xml · Dockerfile   # 백엔드 — Spring Boot 4 / Java 21 (RAG·Agent·거버넌스·서빙)
├── frontend/                     # 프론트 — Next.js (RAG·Agent·진단서·SQL·거버넌스 탭)
├── ml/                           # ML 사이드카 — Python (데이터수집·LoRA·양자화·벤치)
│   ├── data/      수집·데이터셋 스크립트   ├── finetune/  LoRA 학습·평가
│   └── optimize/  양자화·벤치마크          └── RUNBOOK.md 실행 가이드
├── data/vehicle/                 # 도메인 데이터 (샘플 CSV만 커밋, 매뉴얼 PDF는 로컬)
├── docs/                         # 설계 문서 (아키텍처·LoRA·Agent·GraphRAG·디자인·결과)
├── reference/graphrag/           # GraphRAG 레퍼런스(미통합) — 고도화 설계 근거
├── eval/ · sample/ · monitoring/ # 평가 하니스 · 샘플 · Prometheus/Grafana
```

> 백엔드를 `backend/`로 옮기지 않고 루트에 둔 건 의도적 — 기존 Maven·Docker·CI 경로를 유지(리스크↓). 분리는 디렉터리(`frontend/`·`ml/`) + HTTP 경계로 달성.

---

## 🚗 Vehicle Extension (Automotive Domain LLM)

완성된 watsonx 클론 위에 **자동차 밸류체인 NLP**를 얹는 트랙. 현대차 NLP/LLM 직무 JD에 매핑된다.

**2계층 설계** — 본체(Java: RAG·거버넌스·서빙)는 그대로, 모델 제작(Python: 파인튜닝·양자화)을 `ml/` 사이드카로 분리하고 완성 모델을 `llm.provider`로 다시 꽂는다.

| 데이터 | 소스(공개·재현가능) | 경로 |
|---|---|---|
| 정비 매뉴얼 | Internet Archive (공개 PDF) | RAG (`/api/data/ingest-file` → `/api/rag`) |
| 리콜/결함 | NHTSA API (키 불필요) | text-to-SQL (`/api/tabular`, DuckDB) |

> 현대차 공홈(oms.hmc.co.kr)은 로그인·ToS 제약 → 공개·재현가능 소스로 대체. 원문은 `data/vehicle/`(gitignore)에 로컬 인덱싱.

**ML 파이프라인** (`ml/`) — GPU 없는 맥(M2) → 작은 모델 + LoRA + 4bit 양자화 = **온디바이스** 컨셉:

```
fetch_recalls.py / fetch_manuals.py   # 데이터 수집(원본 보존)
        ↓
build_dataset.py                      # 청크 → 한국어 instruction JSONL
        ↓
train_lora.py (MLX, Qwen2.5-1.5B)     # LoRA 파인튜닝 (맥 GPU)
        ↓
quantize_gguf.sh → Ollama 등록         # GGUF Q4 경량화 → 서빙
        ↓
benchmark.py                          # TTFT·tok/s·지연 (양자화 전후)
```

현황: 데이터 수집 + 자동차 RAG(한국어 답변·근거) 검증 완료, LoRA 파인튜닝 진행 중.
자세한 단계는 [ml/RUNBOOK.md](ml/RUNBOOK.md), 설계 이유는 [docs/VEHICLE_ARCHITECTURE.md](docs/VEHICLE_ARCHITECTURE.md).

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
│  Backend: Spring Boot 4 · Java 21 (Temurin/HotSpot)     │
│                                                         │
│  ┌────────────────────────────────────────────────────┐ │
│  │  AI Layer (watsonx.ai analog)                      │ │
│  │  • Chat: multi-LLM, per-request (gemma/granite/..) │ │
│  │  • Embeddings: 768-dim (granite-embedding:278m)    │ │
│  │  • Vision: image Q&A (llava / granite-vision)      │ │
│  │  • OCR grounding: Tesseract (exact text/numbers)   │ │
│  │  • RAG: chunk → embed → hybrid search → rerank     │ │
│  │  • Hybrid: vector + BM25 keyword (RRF fusion)      │ │
│  │  • Reranking: none/llm/mmr/cross (pluggable)       │ │
│  │  • Tabular: CSV/XLSX → text-to-SQL (DuckDB)        │ │
│  └────────────────────────────────────────────────────┘ │
│                                                         │
│  ┌────────────────────────────────────────────────────┐ │
│  │  Data Layer (watsonx.data analog)                  │ │
│  │  • Ingest: Wikipedia / image (vision+OCR) / file   │ │
│  │  • Multi-format: PDF/DOCX/PPTX/XLSX/HTML + HWP/HWPX│ │
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
| Language | Java 21 (Temurin/HotSpot) | OpenJ9는 요청 처리 중 walkStackFrames 크래시로 회피. HOTSPOT-RUNTIME.md |
| Framework | Spring Boot 4.0 | Enterprise standard, fast bootstrap |
| LLM | Ollama (local) 기본, 제공자 교체 가능 | 자체호스팅(키 불필요), LlmClient 추상화로 watsonx/Bedrock 등 설정 교체. LLM-ABSTRACTION.md |
| Chat model | ibm/granite4:latest (default) · multi-LLM | per-request model, whitelist-validated |
| Embedding model | granite-embedding:278m (default) · nomic / 30m / mxbai compared | 768-dim 다국어 승자 (recall 97%, 한국어 11/11). 4종 비교는 EMBEDDINGS.md |
| Vision model | llava / granite-vision | image Q&A + caption (multimodal) |
| OCR | Tesseract (CLI) | exact text/number extraction for grounding |
| Data format | Apache Parquet | Columnar + SNAPPY = 7× smaller than JSON |
| Schema | Avro | Schema-first, evolution-safe |
| Storage | Tiered (JSON hot → Parquet cold) | cheap appends + columnar compaction |
| Catalog | H2 document_catalog (mirror) | SQL-queryable KB metadata; catalog/data split |
| Retrieval | In-memory vector index (brute-force default, LSH opt-in) | exact cosine by default; LSH for sub-linear approximate kNN |
| Vector store | In-memory ↔ pgvector (`vector.store` 스위치) | 영속·확장은 pgvector(HNSW), 차원실험은 인메모리. 인메모리 패리티 35/35. PGVECTOR.md |
| Hybrid search | Vector + BM25 keyword, RRF fusion | lexical recall for exact tokens (IDs, codes) |
| Chunking | fixed / recursive / semantic (pluggable) | recursive default; balance quality vs cost |
| Reranking | none / llm / mmr / cross (pluggable) | two-stage: fetch top-N → rerank → top-K |
| Cross-encoder | DJL + PyTorch + BGE-reranker | dedicated reranker model (Linux/Apple Silicon) |
| Tabular SQL | DuckDB (embedded, in-memory) | text-to-SQL over CSV/XLSX; aggregation RAG can't do |
| Database | H2 (in-memory) | Zero config for governance audit |
| Security | API key / JWT 인증 + 테넌트 격리 강제 | namespace를 코드로 강제(authN/authZ 분리, A/B/C 3안). SECURITY.md |
| CI/CD | GitHub Actions + GitLab CI + Docker | 양쪽 `./mvnw test` 게이트(이식성). 이미지 빌드·푸시(GHCR)는 GitHub만, GitLab은 테스트 게이트 |
| Build | Maven | pom.xml + spring-boot-maven-plugin |
| Frontend | Plain HTML + JS | No framework lock-in, instant load |

---

## Docs

| 문서 | 내용 |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | 컴포넌트·데이터 흐름 |
| [SECURITY.md](docs/SECURITY.md) | 위협모델 · 인증 A/B/C · 테넌트 격리 · 설계결정 |
| [RAG-LANDSCAPE.md](docs/RAG-LANDSCAPE.md) | RAG 종류(Naive/Advanced/Modular, RAPTOR/CRAG/GraphRAG 등) · 로컬 구현 가능성 · MiniWatson 현황 · 로드맵 |
| [PGVECTOR.md](docs/PGVECTOR.md) | pgvector 이관 · HNSW · 인메모리 패리티 |
| [EMBEDDINGS.md](docs/EMBEDDINGS.md) | 임베딩 4종 비교 (승자 granite-278m) |
| [CHUNKING.md](docs/CHUNKING.md) | 청킹 전략 + 약어 확장 |
| [EVALUATION.md](docs/EVALUATION.md) | 골든셋 recall + text-to-SQL |
| [DEBUGGING.md](docs/DEBUGGING.md) | 실전 트러블슈팅 |
| [DECISIONS.md](docs/DECISIONS.md) | 기술 선택 결정 가이드 |
| [OPERATIONS.md](docs/OPERATIONS.md) | 배포 · 재임베딩 · 가용성 · 장애 런북 · 프로덕션 체크리스트 |
| [CLOUD-DEPLOYMENT.md](docs/CLOUD-DEPLOYMENT.md) | 벤더 중립 배포(추론 제공자/호스트 교체), Phase 0 |
| [HOTSPOT-RUNTIME.md](docs/HOTSPOT-RUNTIME.md) | OpenJ9 크래시에서 HotSpot 전환 근거와 절차 |
| [LLM-ABSTRACTION.md](docs/LLM-ABSTRACTION.md) | LlmClient/EmbeddingClient 추상화, 거버넌스 분리 |
| [CICD.md](docs/CICD.md) | CI/CD 파이프라인 현황과 갭 |

---

## Quick Start

### Prerequisites

```bash
# 1. Java 21 (Temurin/HotSpot recommended)
java --version    # → openjdk 21+

# 2. Ollama
brew install ollama
ollama pull ibm/granite4:latest  # chat (default)
ollama pull granite-embedding:278m   # 768-dim embeddings (default, 다국어)
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
  -d '{"question": "What is RAG?", "namespace": "default", "model": "ibm/granite4:latest"}'
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

### Upload a text/document file (Tika + Korean HWP/HWPX)

```bash
curl -X POST http://localhost:8080/api/data/ingest-file \
  -F "file=@report.pdf" -F "namespace=demo"
```

The file is text-extracted, split into chunks, and each chunk stored as an
Article (`title #1`, `#2`, ...). Extraction branches by extension: Tika
(PDF/DOCX/PPTX/XLSX/HTML/txt/md/csv) and HWP/HWPX via hwplib/hwpxlib (see
[docs/INGESTION-FORMATS.md](docs/INGESTION-FORMATS.md)). Returns the list of
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
curl  http://localhost:8080/api/data/index/stats         # mode (brute-force default), vectors, buckets
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
chat-model: ${OLLAMA_CHAT_MODEL:ibm/granite4:latest}
chat-models: ${OLLAMA_CHAT_MODELS:ibm/granite4:latest,gemma4}  # multi-LLM whitelist
embed-model: ${OLLAMA_EMBED_MODEL:granite-embedding:278m}  # 비교 승자 (EMBEDDINGS.md 7절)
vision-model: ${OLLAMA_VISION_MODEL:llava:latest}                       # multimodal
num-predict: ${OLLAMA_NUM_PREDICT:256}

retrieval:
hybrid:
enabled: true          # vector + BM25 (false = vector-only)

storage:
tier:
threshold: ${STORAGE_TIER_THRESHOLD:3}     # hot(JSON) count before compaction → Parquet

vector:
index:
lsh:
enabled: ${VECTOR_LSH_ENABLED:false}       # brute-force default; true = LSH approximate kNN
hyperplanes: ${VECTOR_LSH_HYPERPLANES:16}

chunking:
strategy: recursive   # fixed | recursive | semantic
max-size: 1000        # chars per chunk

rerank:
strategy: mmr         # none | llm | mmr | cross

eval:
overrides:
enabled: ${EVAL_OVERRIDES:true}    # dev/demo = true, prod = false
```

### Profile overrides

| Profile | Storage | When |
|---|---|---|
| `dev` (default) | H2 in-memory | Fast iteration |
| `demo` | H2 file-backed | Persistent demos |
| `prod` | Externalized via env vars | Real deployment |

Switch model without code change:

```bash
OLLAMA_CHAT_MODEL=gemma4 ./mvnw spring-boot:run
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
│   │   ├── GovernanceController.java     # /api/governance/logs · /stats · POST /feedback
│   │   └── TabularController.java        # POST /api/tabular/load · /ask (DuckDB text-to-SQL)
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
│   │   └── RagService.java               # Embed → vector search (top-N) → rerank → top-K
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
- [x] 21 — Eval harness (recall + LLM-as-judge), unit tests, user feedback loop
- [x] 22 — PostgreSQL + pgvector container via Podman (prod profile, persistent governance storage)
- [x] 23 — Korean HWP/HWPX ingest (hwplib/hwpxlib + PrvText fallback); extractText extension dispatch
- [x] 24 — Embedding model comparison (384/768/1024-dim, 4종; 승자 granite-embedding:278m, recall 97% / 한국어 11/11)
- [x] 25 — PgVectorStore — pgvector(HNSW) 영속 vector store, 인메모리 패리티 35/35 (RRF id 붕괴 버그 해결)
- [x] 26 — 청킹 개선: 약어 확장(CAIO→Chief AI Officer)으로 구조적 miss 회복 → 35/35
- [x] 27 — 멀티테넌트 보안: API key/JWT 인증(A/B/C 3안) + 테넌트 격리 강제 (authN/authZ 분리)
- [x] 28 — CI/CD: GitHub Actions + GitLab CI 양쪽 테스트 게이트(./mvnw test, 이식성). 이미지 빌드·푸시(멀티아치 GHCR)는 GitHub Actions
- [x] 29 — 운영 하드닝: 감사 fail-open · Ollama 타임아웃 · rerank fallback · OPERATIONS.md
### 추후 (Backlog — 보류)

핵심 플랫폼(data/ai/governance) + 보안 + CI는 완성. 아래는 운영·심화 영역으로, 배포 산출물(docker-compose.prod, 멀티아치 CI, Oracle 가이드)은 준비됐으나 라이브 비용/시간 대비 우선순위를 미뤘다.

- [ ] 30 — 라이브 배포 (VPS docker-compose, 또는 IBM Cloud Code Engine + watsonx.ai 스왑)
- [ ] 31 — 보안 Tier 2: 프롬프트 인젝션 방어, PII 커버리지 확대, TLS/레이트리밋
- [ ] 32 — 평가 심화(RAGAS류 답변품질), 관측성(metrics/health/tracing)

### 🚗 Vehicle (Automotive) 트랙

자동차 도메인 특화 LLM (현대차 NLP/LLM 직무 JD + 실제 AI 스택 매핑).
상세: [docs/README.md](docs/README.md) · [VEHICLE_EXTENSION_PLAN.md](VEHICLE_EXTENSION_PLAN.md) · [docs/RESULTS.md](docs/RESULTS.md) · [docs/HYUNDAI_NEEDS_ROADMAP.md](docs/HYUNDAI_NEEDS_ROADMAP.md)

- [x] V1 — 데이터 수집: NHTSA 리콜 API(원본 JSON 보존) + Internet Archive 매뉴얼 PDF (`ml/data/fetch_*.py`)
- [x] V2 — 자동차 RAG: `vehicle` 네임스페이스 인제스트 + 약어/DTC 시드 사전 + 한국어 답변·근거
- [x] V3 — 리콜 text-to-SQL 트랙 (`/api/tabular`, DuckDB)
- [x] V4 — 도메인 LoRA 파인튜닝 (Qwen2.5-1.5B, MLX, early-stopping) + base vs FT 평가
- [x] V5 — 경량화(GGUF Q4) → Ollama 서빙 + 추론 벤치(Q4 vs Q8: 메모리½·속도2배)
- [x] V6 — **Agentic Search**: 질문→도구선택(RAG/리콜SQL/BOTH)→실행→한국어 종합 + 트레이스 (`/api/agent`)
- [x] V7 — Next.js 프론트(7탭) + 브라우저 음성(STT/TTS) + 정적 UI 패리티
- [x] V8 — 멀티테이블: NHTSA 불만(complaints) 추가 + 설정 레지스트리(`vehicle.tables`) 동적 선택
- [x] V9 — **차종 종합 진단서**: 리콜+불만+매뉴얼 종합 (`/api/agent/report`, 집계는 결정적 SQL)
- [x] V10 — **이미지 진단→필요 부품**: 사진(Vision+OCR)→매뉴얼 진단→부품 명세(+샘플 견적) (`/api/agent/diagnose-image`, `/estimate`)
- [x] V11 — text-to-SQL 자기수정(self-correction) + 작업별 모델 라우팅(SQL=강한모델, 답변=FT)
- [ ] V12 — (옵션) vLLM provider · 임베딩 파인튜닝 · 온디바이스 음성(Whisper) · 라이브 배포

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
| [docs/EMBEDDINGS.md](docs/EMBEDDINGS.md) | Embedding model comparison (384/768/1024-dim), prefix convention, measurement harness |
| [docs/INGESTION-FORMATS.md](docs/INGESTION-FORMATS.md) | Multi-format ingest — Tika + Korean HWP/HWPX (PrvText fallback), extension dispatch |
| [docs/TABULAR-SQL.md](docs/TABULAR-SQL.md) | Tabular text-to-SQL over CSV/XLSX with DuckDB — aggregation path RAG can't do, SELECT-only guard |
| [docs/EVALUATION.md](docs/EVALUATION.md) | Retrieval eval harness, rerank/hybrid sweep, findings (llm rerank can hurt) |
| [docs/TESTING.md](docs/TESTING.md) | JUnit unit tests; how a test caught a Korean-phone PII gap |
| [docs/VERIFICATION.md](docs/VERIFICATION.md) | How each feature was verified — unit / offline eval / curl / UI |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | Postgres + pgvector via Podman, profiles, and 3 real deployment gotchas |
| [docs/H2-CONSOLE.md](docs/H2-CONSOLE.md) | H2 web console — enable, login, SQL cookbook, prod warning |

**Live (interactive) API docs**: run the app, then open [`http://localhost:8080/swagger-ui.html`](http://localhost:8080/swagger-ui.html). See [`SWAGGER-SETUP.md`](SWAGGER-SETUP.md) to enable.

---

## Author

**Daeyeop Kim** — [github.com/dea980](https://github.com/dea980) · kdea989@gmail.com
