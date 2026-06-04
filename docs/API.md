# MiniWatson — REST API Reference

> Base URL: `http://localhost:8080`
> Content-Type: `application/json` (별도 명시 없으면)
> Live interactive docs: `http://localhost:8080/swagger-ui.html` (Swagger 설정 후)

---

## 목차

1. [Data Ingestion](#1-data-ingestion)
   - `POST /api/data/ingest`
   - `POST /api/data/ingest-batch`
   - `GET  /api/data/articles`
   - `GET  /api/data/count`
2. [RAG (Retrieval-Augmented Generation)](#2-rag)
   - `POST /api/rag/ask`
3. [Governance / Audit](#3-governance--audit)
   - `GET /api/audit/logs`
   - `GET /api/audit/logs/{id}`
4. [Health](#4-health)

---

## 1. Data Ingestion

Wikipedia REST API에서 article을 가져와 embedding을 생성하고 Parquet에 영구 저장합니다.

### `POST /api/data/ingest`

Single article ingestion. Query parameter 방식.

**Request**
```bash
curl -X POST "http://localhost:8080/api/data/ingest?title=Retrieval-augmented%20generation"
```

| Param | In    | Type   | Required | Description                       |
|-------|-------|--------|----------|-----------------------------------|
| title | query | string | ✓        | Wikipedia article title (URL-encode) |

**Response — 200 OK**
```json
{
  "id": 1,
  "title": "Retrieval-augmented generation",
  "summary": "Retrieval-augmented generation (RAG) is a technique...",
  "url": "https://en.wikipedia.org/wiki/Retrieval-augmented_generation",
  "ingestedAt": "2026-06-05T00:48:40.411367"
}
```

> `embedding` 필드는 `@JsonProperty(WRITE_ONLY)`로 응답에서 숨겨집니다 (768-dim 노이즈 방지).

---

### `POST /api/data/ingest-batch`

여러 article을 한 번에 ingest. 일부 실패해도 나머지는 계속 진행.

**Request**
```bash
curl -X POST http://localhost:8080/api/data/ingest-batch \
  -H "Content-Type: application/json" \
  -d '{"topics": ["Retrieval-augmented generation", "Vector database", "Embedding"]}'
```

**Request Body**
```json
{
  "topics": ["string", "..."]
}
```

**Response — 200 OK**
```json
{
  "success": true,
  "ingested": 3,
  "failed": 0,
  "articles": [
    { "id": 1, "title": "...", "summary": "...", "url": "...", "ingestedAt": "..." }
  ],
  "errors": []
}
```

**Response — partial failure**
```json
{
  "success": true,
  "ingested": 2,
  "failed": 1,
  "articles": [ /* 2 articles */ ],
  "errors": [
    { "title": "Nonexistent Topic", "error": "404 Not Found" }
  ]
}
```

---

### `GET /api/data/articles`

전체 article 목록 조회 (Parquet에서 lazy load).

**Request**
```bash
curl http://localhost:8080/api/data/articles
```

**Response — 200 OK**
```json
[
  { "id": 1, "title": "...", "summary": "...", "url": "...", "ingestedAt": "..." },
  { "id": 2, ... }
]
```

---

### `GET /api/data/count`

빠른 카운트 (dashboard용).

**Request**
```bash
curl http://localhost:8080/api/data/count
```

**Response**
```json
{ "count": 3 }
```

---

## 2. RAG

### `POST /api/rag/ask`

질문 → embedding → top-K cosine similarity → augmented prompt → Gemma4 (`think: false`) → answer + sources.

**Request**
```bash
curl -X POST http://localhost:8080/api/rag/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "What is RAG?"}'
```

**Request Body**
```json
{
  "question": "string"
}
```

**Response — 200 OK**
```json
{
  "answer": "Retrieval-augmented generation (RAG) is a technique that enables LLMs to retrieve and incorporate new information from external data sources before responding...",
  "sources": [
    {
      "id": 1,
      "title": "Retrieval-augmented generation",
      "url": "https://en.wikipedia.org/wiki/Retrieval-augmented_generation",
      "similarity": 0.8421
    },
    {
      "id": 2,
      "title": "Vector database",
      "url": "https://en.wikipedia.org/wiki/Vector_database",
      "similarity": 0.6310
    }
  ],
  "model": "gemma4",
  "tookMs": 1842
}
```

**Behavior**
- `sources`는 cosine similarity desc 정렬, default top-3.
- `tookMs`는 embedding → retrieval → LLM 전체 wall-clock.
- ingest된 article이 없으면 `sources: []` + LLM-only 답변.

---

## 3. Governance / Audit

모든 RAG 호출은 H2 in-memory DB에 audit log로 기록됩니다. (production: PostgreSQL/Cloudant)

### `GET /api/audit/logs`

전체 audit log (시간역순).

**Request**
```bash
curl http://localhost:8080/api/audit/logs
```

**Response**
```json
[
  {
    "id": 12,
    "userId": "anonymous",
    "endpoint": "/api/rag/ask",
    "question": "What is RAG?",
    "answerPreview": "Retrieval-augmented generation (RAG) is...",
    "sourceCount": 3,
    "model": "gemma4",
    "tookMs": 1842,
    "createdAt": "2026-06-05T00:50:11.231"
  }
]
```

---

### `GET /api/audit/logs/{id}`

단일 log 상세.

**Request**
```bash
curl http://localhost:8080/api/audit/logs/12
```

**Response** — 동일 schema, 단일 객체.

---

## 4. Health

### `GET /actuator/health`

Spring Boot Actuator. UP/DOWN.

```bash
curl http://localhost:8080/actuator/health
```

```json
{ "status": "UP" }
```

---

## Error Responses

표준 Spring Boot 에러 응답:

```json
{
  "timestamp": "2026-06-05T00:42:05.932Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Required parameter 'title' is not present.",
  "path": "/api/data/ingest"
}
```

| Status | Meaning                                                   |
|--------|-----------------------------------------------------------|
| 200    | Success                                                   |
| 400    | Missing/invalid parameter, malformed JSON                 |
| 404    | Article (Wikipedia 404) — surfaced inside batch `errors` |
| 500    | Ollama down, Parquet I/O error, unexpected exception      |
| 503    | Ollama timeout (>30s default)                             |

---

## Data Types

### Article

| Field       | Type           | Notes                                       |
|-------------|----------------|---------------------------------------------|
| id          | long           | Auto-increment (size of store + 1)          |
| title       | string         | Wikipedia title                             |
| summary     | string         | Wikipedia `/page/summary` extract           |
| url         | string         | Canonical Wikipedia URL                     |
| ingestedAt  | string (ISO-8601) | LocalDateTime, e.g. `2026-06-05T00:48:40` |
| embedding   | float[768]     | Hidden from API responses (WRITE_ONLY)      |

### Source (in RAG response)

| Field      | Type   | Notes                              |
|------------|--------|------------------------------------|
| id         | long   | Reference to Article.id            |
| title      | string |                                    |
| url        | string |                                    |
| similarity | float  | Cosine similarity, range [-1, 1]   |

### AuditLog

| Field         | Type     | Notes                                   |
|---------------|----------|-----------------------------------------|
| id            | long     | DB auto-PK                              |
| userId        | string   | "anonymous" until auth added            |
| endpoint      | string   | e.g. `/api/rag/ask`                     |
| question      | string   | Full question                           |
| answerPreview | string   | First 200 chars of answer               |
| sourceCount   | int      | Number of sources used                  |
| model         | string   | `gemma4`                                |
| tookMs        | long     | Wall-clock ms                           |
| createdAt     | datetime | Server time                             |

---

## Notes

- **Local-only**: Wikipedia REST + Ollama localhost:11434. No external paid APIs.
- **Compression**: Parquet+SNAPPY achieves ~7× ratio over JSON (54KB → 7.8KB observed).
- **Anti-corruption**: `@JsonIgnoreProperties(ignoreUnknown=true)` on Wikipedia DTOs.
- **think: false**: Gemma4 reasoning tokens disabled to ensure full answer fits in `num_predict`.
