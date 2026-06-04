# MiniWatson

> Mini watsonx-style learning RAG platform with Spring Boot + Ollama LLM

Educational platform that mirrors IBM watsonx's 3-layer architecture
(data · ai · governance) at a small scale. Built to learn how
enterprise GenAI platforms work end-to-end.

## 🏗️ Architecture

Inspired by IBM watsonx — `data`, `ai`, `governance` layers:

\`\`\`
┌─────────────────────────────────────────────┐
│  Frontend (planned): JavaScript dashboard   │
└──────────────────┬──────────────────────────┘
│ JSON (REST)
┌──────────────────▼──────────────────────────┐
│  Backend: Spring Boot 4 + Java 21           │
│                                             │
│  ┌───────────────────────────────────────┐  │
│  │  [watsonx.ai mini]                    │  │
│  │  Ollama LLM (gemma4) integration      │  │
│  │  via RestTemplate                     │  │
│  └───────────────────────────────────────┘  │
│                                             │
│  ┌───────────────────────────────────────┐  │
│  │  [watsonx.data mini] (planned)        │  │
│  │  Public API → Parquet storage         │  │
│  │  SQL retrieval                        │  │
│  └───────────────────────────────────────┘  │
│                                             │
│  ┌───────────────────────────────────────┐  │
│  │  [watsonx.governance mini] (planned)  │  │
│  │  H2 DB Q&A audit log                  │  │
│  └───────────────────────────────────────┘  │
└─────────────────────────────────────────────┘
│
▼
Ollama (local LLM)
\`\`\`

## 🛠️ Tech Stack

- **Language**: Java 21 (IBM Semeru JDK)
- **Framework**: Spring Boot 4.0.6
- **Build**: Maven
- **LLM**: Ollama (gemma4 model, local hosting)
- **HTTP Client**: RestTemplate
- **DB**: H2 (in-memory)
- **JSON**: Jackson (auto)
- **Code Generation**: Lombok

## 🚀 Quick Start

### Prerequisites

- Java 21+ (IBM Semeru recommended)
- Maven 3.9+
- [Ollama](https://ollama.com) installed locally
- Ollama model: `ollama pull gemma4`

### Run

\`\`\`bash
# 1. Start Ollama
ollama serve

# 2. Start Spring Boot
./mvnw spring-boot:run

# 3. Test the API
curl -X POST http://localhost:8080/api/ask \\
-H "Content-Type: application/json" \\
-d '{"question": "What is RAG? Answer in 2 sentences."}'
\`\`\`

## 📁 Project Structure

\`\`\`
miniwatson/
├── src/main/java/com/miniwatson/
│   ├── MiniwatsonApplication.java       # Spring Boot entry
│   ├── controller/
│   │   ├── HelloController.java         # GET /api/hello
│   │   └── AskController.java           # POST /api/ask
│   ├── service/
│   │   └── OllamaService.java           # LLM integration (watsonx.ai mini)
│   └── dto/
│       ├── AskRequest.java              # User request
│       ├── OllamaRequest.java           # Ollama API request
│       └── OllamaResponse.java          # Ollama API response
├── src/main/resources/
│   └── application.yml                  # Spring Boot config
├── pom.xml                              # Maven dependencies
└── README.md
\`\`\`

## 🔌 API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/hello` | Health check |
| POST | `/api/ask` | Send question to LLM |
| GET | `/h2-console` | H2 database UI |

### Example: POST /api/ask

**Request**:
\`\`\`json
{
"question": "What is RAG?"
}
\`\`\`

**Response**:
\`\`\`
"RAG (Retrieval-Augmented Generation) is an AI architecture..."
\`\`\`

## 🗺️ Roadmap

- [x] **Day 1** — Spring Boot setup + first endpoint
- [x] **Day 2** — Ollama integration (watsonx.ai mini)
- [ ] **Day 3** — H2 governance layer (audit log)
- [ ] **Day 4** — Public API + Parquet storage (watsonx.data mini)
- [ ] **Day 5** — RAG with embeddings (nomic-embed-text)
- [ ] **Day 6** — Frontend dashboard
- [ ] **Day 7** — Documentation + demo video

## 💡 Why This Project

Built to understand how enterprise GenAI platforms (specifically IBM watsonx)
work internally. By recreating the 3-layer structure at a small scale:

- **Data layer** — how multi-source content is ingested and stored
- **AI layer** — how LLMs are integrated as a service
- **Governance layer** — why audit trails and lineage matter

Key insight from building this:
**"In enterprise GenAI, the model is the easy part.
The pipeline, governance, and integration determine the value."**

## 📚 What I Learned

- **Spring Boot 3-layer architecture**: Controller → Service → Repository pattern
- **Local LLM hosting**: Ollama as a sovereignty-first AI deployment
- **REST API integration**: RestTemplate, JSON serialization via Jackson
- **DTO pattern**: Why separate request/response models matter
- **Configuration**: application.yml, environment-specific settings

## 🔗 References

- [IBM watsonx](https://www.ibm.com/watsonx)
- [Ollama](https://ollama.com)
- [Spring Boot](https://spring.io/projects/spring-boot)
- [IBM IBV CEO Study 2026](https://www.ibm.com/thought-leadership/institute-business-value/c-suite-study)

## 👤 Author

**Daeyeop Kim**

- Built during preparation for IBM Consulting Internship
- Background: AMC Digital Medicine Lab, LG CNS Global Finance Promotion Team
- Interests: Enterprise AI, healthcare data, RAG architectures

## 📄 License

MIT