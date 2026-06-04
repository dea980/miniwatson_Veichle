# MiniWatson — Governance & Audit

Every RAG call is logged. This is MiniWatson's small-scale parity for **watsonx.governance**.

---

## 1. Why a Governance Layer?

LLM applications are unauditable by default. If a customer asks "what model gave me this answer, on what data, at what time?", you must answer in seconds.

MiniWatson treats every RAG call as a **regulated event**:

- **Who** asked (`userId`)
- **What** was asked (`question`)
- **What** came back (`answerPreview`)
- **Which** sources were used (`sourceCount`)
- **Which** model produced the answer (`model`)
- **How long** it took (`tookMs`)
- **When** (`createdAt`)

This is the minimum schema to satisfy:
- EU AI Act Art. 12 (record-keeping)
- ISO/IEC 42001 (AI management system audit trail)
- SOC 2 CC7 (system monitoring)
- IBM internal "AI usage transparency"

---

## 2. Schema — `AuditLog`

```java
@Entity
@Table(name = "audit_log")
public class AuditLog {
    @Id @GeneratedValue
    private Long id;

    private String userId;          // "anonymous" until auth added
    private String endpoint;        // "/api/rag/ask"
    @Column(length = 2000)
    private String question;
    @Column(length = 2000)
    private String answerPreview;   // first 200 chars
    private Integer sourceCount;
    private String model;           // "gemma4"
    private Long tookMs;
    private LocalDateTime createdAt;
}
```

| Column         | Type        | Purpose                          |
|----------------|-------------|----------------------------------|
| id             | bigint PK   | Auto-increment                   |
| user_id        | varchar     | Identity (auth-aware later)      |
| endpoint       | varchar     | Which route was hit              |
| question       | varchar(2k) | Full user input                  |
| answer_preview | varchar(2k) | Truncated answer for fast scan   |
| source_count   | int         | Sources retrieved                |
| model          | varchar     | Foundation model name+version    |
| took_ms        | bigint      | End-to-end latency               |
| created_at     | timestamp   | UTC                              |

---

## 3. Storage

| Profile | Backend                | Retention      | Notes                             |
|---------|------------------------|----------------|-----------------------------------|
| `dev`   | H2 in-memory           | per-JVM session | Reset on restart                  |
| `demo`  | H2 file (`./data/h2/`) | per-laptop      | Survives restart                  |
| `prod`  | PostgreSQL / Cloudant  | 13 months       | Reserved (placeholder)            |

---

## 4. Write Path

Every entry-point that touches an LLM goes through `AuditService.log(...)`:

```java
public void log(String endpoint, String question, String answer,
                int sourceCount, String model, long tookMs) {
    AuditLog entry = new AuditLog();
    entry.setUserId(SecurityContext.userId());        // "anonymous" today
    entry.setEndpoint(endpoint);
    entry.setQuestion(question);
    entry.setAnswerPreview(truncate(answer, 200));
    entry.setSourceCount(sourceCount);
    entry.setModel(model);
    entry.setTookMs(tookMs);
    entry.setCreatedAt(LocalDateTime.now());
    repository.save(entry);
}
```

Called at the **end** of `RagService.ask(...)`, after the LLM responds.

> **Failure isolation**: audit write failure must NOT fail the user request. Wrap in try/catch; log at WARN.

---

## 5. Read Path — API

| Endpoint                        | Description                |
|---------------------------------|----------------------------|
| `GET /api/audit/logs`           | All logs, newest first     |
| `GET /api/audit/logs/{id}`      | Single log by ID           |
| `GET /api/audit/logs?endpoint=` | Filter by endpoint         |
| `GET /api/audit/logs?model=`    | Filter by model name       |

(See `docs/API.md` for request/response examples.)

---

## 6. H2 Console (dev only)

H2 console is exposed at `/h2-console` in dev profile.

| Setting     | Value                       |
|-------------|-----------------------------|
| JDBC URL    | `jdbc:h2:mem:miniwatson`    |
| User        | `sa`                        |
| Password    | (empty)                     |

Useful SQL:
```sql
-- Recent activity
SELECT id, user_id, endpoint, model, took_ms, created_at
FROM audit_log
ORDER BY created_at DESC
LIMIT 50;

-- Per-model usage
SELECT model, COUNT(*) AS calls, AVG(took_ms) AS avg_ms
FROM audit_log
GROUP BY model;

-- Slowest 10 calls
SELECT id, question, took_ms, source_count
FROM audit_log
ORDER BY took_ms DESC
LIMIT 10;
```

---

## 7. Mapping to watsonx.governance

| MiniWatson concept     | watsonx.governance concept             |
|------------------------|----------------------------------------|
| `AuditLog` row         | Model card · decision record           |
| `model` field          | AI Factsheet model identifier          |
| `sourceCount` + sources| Provenance / lineage                   |
| `tookMs`               | Performance monitoring                 |
| `endpoint`             | Use-case tag                           |
| `userId`               | Subject (consent / data-subject right) |
| Filter API             | Risk dashboard query interface         |

---

## 8. What's NOT in Scope (yet)

| Topic                       | Note                                                                |
|-----------------------------|---------------------------------------------------------------------|
| PII redaction               | `question`/`answer` stored as-is — add masking before prod          |
| Cryptographic chaining      | Each entry standalone — no hash chain for tamper-evidence           |
| Right-to-be-forgotten        | No DELETE API — `userId`-scoped purge to be added                   |
| Cost attribution            | `tookMs` only — no `tokens_in/out` (Ollama doesn't expose easily)   |
| Streaming events            | Synchronous write — Kafka/event-bus version is production direction |
| Per-tenant separation       | Single tenant assumed                                               |

These are documented gaps, not silent omissions. Production version would add each item.

---

## 9. Why This Matters for IBM

1. **watsonx parity** — three pillars (data · ai · governance) all visible.
2. **Regulatory awareness** — schema designed against EU AI Act / ISO 42001.
3. **Anti-corruption** — Wikipedia DTO never reaches the audit log directly.
4. **Local-first sovereignty** — no audit data leaves the laptop.
5. **Transparent gaps** — §8 honestly lists what production would add.
