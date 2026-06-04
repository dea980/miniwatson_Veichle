# MiniWatson — REST API Reference

> Base URL: `http://localhost:8080` (default profile=dev)
> Content-Type: `application/json` (request/response)
> 인증: 없음 (학습 프로젝트)

---

## Endpoint 요약

| Method | Path | 기능 | 컨트롤러 |
|---|---|---|---|
| GET  | `/api/hello`               | liveness                 | `HelloController` |
| GET  | `/api/version`             | version string           | `HelloController` |
| POST | `/api/ask`                 | direct LLM (no RAG)      | `AskController` |
| POST | `/api/rag/ask`             | RAG Q&A                  | `RagController` |
| POST | `/api/data/ingest`         | single article ingest    | `DataController` |
| POST | `/api/data/ingest-batch`   | batch article ingest     | `DataController` |
| GET  | `/api/data/articles`       | 전체 article 목록         | `DataController` |
| GET  | `/api/data/count`          | article 개수             | `DataController` |
| GET  | `/api/governance/logs`     | LLM 호출 audit log 전체   | `GovernanaceController` |
| GET  | `/h2-console`              | H2 web console (dev/demo) | (Spring Boot) |

모든 응답은 별도 wrapper 없이 도메인 객체 그대로. 4xx/5xx는 Spring 기본 에러 응답 (`timestamp, status, error, message, path`).

---

## 1. GET /api/hello

**Response 200**
```
hello watsonx
```
(Plain text)

대시보드의 헬스체크용. 응답 문자열 바꾸면 `static/js/app.js` 동기화 필요.

---

## 2. GET /api/version

**Response 200**
```
1.0
```

---

## 3. POST /api/ask  (direct LLM, no retrieval)

**Request**
```http
POST /api/ask
Content-Type: application/json

{ "question": "What is Spring Boot?" }
```

**Response 200**
```
Spring Boot is a framework... (LLM 답변 텍스트)
```
(Plain text. 컨트롤러가 `String` 반환)

**부수효과**: `query_log` 테이블에 한 행 insert (governance audit).

---

## 4. POST /api/rag/ask

**Request**
```http
POST /api/rag/ask
Content-Type: application/json

{ "question": "What is RAG?" }
```

**Response 200**
```json
{
  "answer": "Retrieval-Augmented Generation combines...",
  "sources": [
    {
      "id": 1,
      "title": "Retrieval-augmented generation",
      "summary": "Retrieval-augmented generation (RAG) is...",
      "url": "https://en.wikipedia.org/wiki/Retrieval-augmented_generation",
      "ingestedAt": "2026-06-04T23:55:12.345"
    },
    {
      "id": 4,
      "title": "Vector database",
      "summary": "A vector database is...",
      "url": "https://en.wikipedia.org/wiki/Vector_database",
      "ingestedAt": "2026-06-04T23:56:01.123"
    }
  ]
}
```

- `sources` 는 cosine top-K (=2) 결과.
- `embedding` 필드는 `@JsonProperty(WRITE_ONLY)` 로 응답에서 제거됨.

**Response 500** (knowledge base 비어 있음)
```json
{"timestamp":"...","status":500,"error":"Internal Server Error",
 "message":"No articles in knowledge base.","path":"/api/rag/ask"}
```

---

## 5. POST /api/data/ingest

**Request**
```http
POST /api/data/ingest?title=Retrieval-augmented_generation
```
(쿼리 파라미터 — 본문 없음. title은 Wikipedia URL 표기법: 공백은 `_`)

**Response 200**
```json
{
  "id": 1,
  "title": "Retrieval-augmented generation",
  "summary": "...",
  "url": "https://en.wikipedia.org/wiki/Retrieval-augmented_generation",
  "ingestedAt": "2026-06-04T23:55:12.345"
}
```
(embedding 필드는 응답에 노출되지 않음 — 저장은 됨)

**Failure modes**
- Wikipedia 404 (잘못된 title) → 500
- User-Agent 누락 → 코드에 박혀 있어 정상 (없는 경우 Wikipedia 403)
- Ollama down → embedding 단계 실패 → 500

---

## 6. POST /api/data/ingest-batch

**Request**
```http
POST /api/data/ingest-batch
Content-Type: application/json

{ "topics": ["RAG", "Vector database", "Embedding"] }
```

**Response 200 (전부 성공)**
```json
{
  "success": true,
  "ingested": 3,
  "failed": 0,
  "articles": [ {...}, {...}, {...} ],
  "errors": []
}
```

**Response 200 (부분 실패)** — 일부 실패해도 200 OK 유지
```json
{
  "success": true,
  "ingested": 2,
  "failed": 1,
  "articles": [ {...}, {...} ],
  "errors": [
    { "title": "NonexistentTopic_xyz", "error": "404 Not Found: ..." }
  ]
}
```

**Response 200 (빈 입력)**
```json
{ "success": false, "error": "Request body must contain non-empty 'topics' array" }
```
(HTTP 200 그대로 — 의도된 단순함. 엄밀한 400 응답으로 바꾸려면 `ResponseEntity.badRequest()` 사용)

---

## 7. GET /api/data/articles

**Response 200**
```json
[
  {
    "id": 1, "title": "...", "summary": "...", "url": "...",
    "ingestedAt": "2026-06-04T23:55:12.345"
  },
  ...
]
```
embedding 미노출.

비어 있을 때는 `[]` 반환 (200 OK).

---

## 8. GET /api/data/count

**Response 200**
```json
{ "count": 5 }
```

대시보드 카드 표시용. 내부적으로는 `loadAll().size()` — N이 커지면 별도 `count()` 메서드 추가 검토.

---

## 9. GET /api/governance/logs

**Response 200**
```json
[
  {
    "id": 1,
    "question": "What is RAG?",
    "answer": "Retrieval-Augmented Generation...",
    "model": "gemma4",
    "latencyMs": 2341,
    "createdAt": "2026-06-04T23:55:14.567"
  },
  ...
]
```

`JpaRepository.findAll()` 기본 순서 (DB-dependent). 정렬/페이지네이션은 미구현.

---

## 10. H2 Console (dev / demo)

URL: `http://localhost:8080/h2-console`

| Profile | JDBC URL |
|---|---|
| `dev`  | `jdbc:h2:mem:miniwatson` |
| `demo` | `jdbc:h2:file:./data/miniwatson;AUTO_SERVER=TRUE` |

User: `sa` / Password: (empty)

샘플 쿼리:
```sql
SELECT model, COUNT(*) AS calls, AVG(latency_ms) AS avg_latency
FROM query_log GROUP BY model;
```

---

## 11. cURL Cheatsheet

```bash
# liveness
curl http://localhost:8080/api/hello

# single ingest
curl -X POST "http://localhost:8080/api/data/ingest?title=RAG"

# batch ingest
curl -X POST http://localhost:8080/api/data/ingest-batch \
  -H 'Content-Type: application/json' \
  -d '{"topics":["RAG","Vector database","Embedding"]}'

# list articles
curl http://localhost:8080/api/data/articles | jq '.[] | {id,title}'

# direct LLM
curl -X POST http://localhost:8080/api/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"What is Java 21?"}'

# RAG
curl -X POST http://localhost:8080/api/rag/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"What is RAG?"}' | jq

# governance
curl http://localhost:8080/api/governance/logs | jq '.[-5:]'
```

---

## 12. Error Schema (Spring 기본)

모든 4xx/5xx는 다음 형태:
```json
{
  "timestamp": "2026-06-04T23:55:14.567+00:00",
  "status": 500,
  "error": "Internal Server Error",
  "message": "...",
  "path": "/api/rag/ask"
}
```

명시적 `@ControllerAdvice` 가 없음. 운영 적용 시 추가 필요 (특히 외부 시스템 다운 / 입력 검증 / 모델 응답 누락 케이스).

---

## 13. Known endpoint quirks

| Quirk | 설명 |
|---|---|
| `/api/data/ingest` 가 query param 방식 | RESTful하게는 `{title}` body 또는 path가 더 자연스러우나 호환성 유지 |
| `/api/ask` 응답이 `text/plain`-ish | Spring이 `String` 반환 시 `text/plain;charset=UTF-8` 으로 응답 |
| `/api/data/ingest-batch` 의 실패 시에도 200 | wrapper 응답 (`success:false` field 로 식별) |
| URL의 `governance` 는 정상, 컨트롤러 클래스명만 `Governanace` (오타) | 외부에는 영향 없음 |
| RAG `sources[].embedding` 없음 | 의도된 동작 ([SDS §4.1](./SDS.md)) |
