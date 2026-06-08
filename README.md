# MiniWatson

> A miniature watsonx-style platform — built end-to-end from scratch  
> Spring Boot · Ollama · Parquet · 768-dim embeddings · RAG

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
│  │  • Ollama gemma4 — chat (think: false)             │ │
│  │  • Ollama nomic-embed-text — 768-dim embeddings    │ │
│  │  • RAG: cosine similarity + augmented prompt       │ │
│  └────────────────────────────────────────────────────┘ │
│                                                         │
│  ┌────────────────────────────────────────────────────┐ │
│  │  Data Layer (watsonx.data analog)                  │ │
│  │  • Wikipedia REST API ingestion                    │ │
│  │  • Parquet (Avro schema, SNAPPY compression)       │ │
│  │  • 7× smaller than JSON                            │ │
│  └────────────────────────────────────────────────────┘ │
│                                                         │
│  ┌────────────────────────────────────────────────────┐ │
│  │  Governance Layer (watsonx.governance analog)      │ │
│  │  • Auto audit log every Q&A in H2                  │ │
│  │  • Tracks model, latency, timestamp                │ │
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
| Chat model | gemma4 | think: false for fast inference |
| Embedding model | nomic-embed-text | 768-dim, runs locally |
| Data format | Apache Parquet | Columnar + SNAPPY = 7× smaller than JSON |
| Schema | Avro | Schema-first, evolution-safe |
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
ollama pull gemma4
ollama pull nomic-embed-text

# 3. Start Ollama server (separate terminal)
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

### Ask a RAG question

```bash
curl -X POST http://localhost:8080/api/rag/ask \\
-H "Content-Type: application/json" \\
-d '{"question": "What is RAG?"}'
```

Returns answer plus the top-K source articles used for grounding.

### List articles

```bash
curl http://localhost:8080/api/data/articles
```

### Audit trail

```bash
curl http://localhost:8080/api/governance/logs
```

Every LLM call is logged: question, answer, model, latency (ms), timestamp.


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
embed-model: ${OLLAMA_EMBED_MODEL:nomic-embed-text}
num-predict: ${OLLAMA_NUM_PREDICT:500}
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
│   │   ├── RagController.java            # POST /api/rag/ask
│   │   ├── DataController.java           # /api/data/*
│   │   └── GovernanceController.java     # GET /api/governance/logs
│   ├── service/
│   │   ├── OllamaService.java            # Chat: gemma4 + think:false
│   │   ├── EmbeddingService.java         # Embed: nomic-embed-text
│   │   ├── IngestionService.java         # Wikipedia → Article → Store
│   │   └── RagService.java               # Embed + similarity + augment
│   ├── data/
│   │   ├── Article.java                  # POJO + embedding (write-only)
│   │   ├── WikipediaResponse.java        # External API DTO
│   │   └── ArticleParquetStore.java      # Parquet read/write
│   ├── governance/
│   │   ├── QueryLog.java                 # JPA entity
│   │   └── QueryLogRepository.java       # Spring Data JPA
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
├── data/
│   ├── articles.parquet                  # Knowledge base (auto-generated)
│   └── articles.json.backup              # Legacy JSON (reference)
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

---

## Roadmap

- [x] 1 — Spring Boot setup
- [x] 2 — Ollama integration (watsonx.ai analog)
- [x] 3 — H2 audit log (watsonx.governance analog)
- [x] 4 — Wikipedia → Parquet (watsonx.data analog)
- [x] 5 — RAG with embeddings + cosine similarity
- [x] 6 — Frontend dashboard (IBM Carbon-style)
- [ ] 7 — deployment notes
- [ ] Multi-tenant article namespacing
- [ ] vector index for sub-linear retrieval

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

## Author

**Daeyeop Kim**  
[github.com/dea980](https://github.com/dea980) · kdea989@gmail.com# README snippet — paste into existing README.md

Add this near the top, under your project tagline, so visitors can find the docs immediately.

---

## 📚 Documentation

| Doc                            | What's inside                                              |
|--------------------------------|------------------------------------------------------------|
| [docs/API.md](docs/API.md)             | REST API reference — every endpoint with curl + schemas    |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Component diagram, request flows, watsonx mapping          |
| [docs/DATA-MODEL.md](docs/DATA-MODEL.md)     | Article schema, Avro + Parquet, anti-corruption layer      |
| [docs/GOVERNANCE.md](docs/GOVERNANCE.md)     | Audit log schema, watsonx.governance parity                |
| [docs/H2-CONSOLE.md](docs/H2-CONSOLE.md)     | H2 web console — enable, login, SQL cookbook, prod warning |

**Live (interactive) API docs**: run the app, then open [`http://localhost:8080/swagger-ui.html`](http://localhost:8080/swagger-ui.html).
See [`SWAGGER-SETUP.md`](SWAGGER-SETUP.md) to enable.
# README snippet — paste into existing README.md

Add this near the top, under your project tagline, so visitors can find the docs immediately.

---

## 📚 Documentation

| Doc                            | What's inside                                              |
|--------------------------------|------------------------------------------------------------|
| [docs/API.md](docs/API.md)             | REST API reference — every endpoint with curl + schemas    |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Component diagram, request flows, watsonx mapping          |
| [docs/DATA-MODEL.md](docs/DATA-MODEL.md)     | Article schema, Avro + Parquet, anti-corruption layer      |
| [docs/GOVERNANCE.md](docs/GOVERNANCE.md)     | Audit log schema, watsonx.governance parity                |
| [docs/H2-CONSOLE.md](docs/H2-CONSOLE.md)     | H2 web console — enable, login, SQL cookbook, prod warning |

**Live (interactive) API docs**: run the app, then open [`http://localhost:8080/swagger-ui.html`](http://localhost:8080/swagger-ui.html).
See [`SWAGGER-SETUP.md`](SWAGGER-SETUP.md) to enable.
