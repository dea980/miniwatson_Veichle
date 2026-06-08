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
| ~~PII redaction~~ ✅ DONE    | Implemented — see section 10 (regex masking before audit-log persist)      |
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
5. **Transparent gaps** — section 8 honestly lists what production would add.

---

## 10. PII 마스킹 (민감정보 자동 가림) — 구현됨

감사 로그는 질문·프롬프트·답변을 그대로 쌓는다. 거기에 이메일·전화번호·주민번호·
카드번호 같은 **개인식별정보(PII)** 가 섞이면, 로그 자체가 유출 위험이 된다.
그래서 **로그에 저장되기 직전** 단계에서 PII를 마스킹한다. 이것이
watsonx.governance가 강조하는 "민감정보 보호 + 감사 가능성"의 소규모 구현이다.

### 동작 흐름

```
LLM 호출
  → 응답 받음
  → PiiRedactionService.redact(question) / redact(answer)   ← 마스킹 + 건수 카운트
  → QueryLog 저장: 마스킹된 텍스트 + piiCount
  → 사용자에겐 원본 답변 반환                                 ← 기능은 그대로
```

핵심 원칙: **사용자 응답은 원본, 저장되는 감사 로그만 마스킹.** 기능을 해치지 않고
기록만 보호한다.

### 탐지 패턴 (정규식 기반)

| 라벨 | 대상 | 예시 → 결과 |
|---|---|---|
| `[EMAIL]` | 이메일 | `john@acme.com` → `[EMAIL]` |
| `[PHONE]` | 전화번호 | `010-1234-5678` → `[PHONE]` |
| `[SSN]` | 주민/사회보장번호 패턴 | `123-45-6789` → `[SSN]` |
| `[CARD]` | 13~16자리 카드번호 | `4111 1111 1111 1111` → `[CARD]` |

`QueryLog`에는 `piiCount`(이 질의에서 마스킹된 건수)가 함께 저장돼, 대시보드
Audit Trail에서 "🔒 N"으로 표시된다 → 거버넌스가 민감정보를 실제로 잡아냈음을 가시화.

### 예시

요청:
```json
{ "question": "My email is john@acme.com, what is RAG?" }
```
저장된 로그:
```
question: "My email is [EMAIL], what is RAG?"
piiCount: 1
```

### 한계 (정직하게)

- **정규식 기반**이라 정형 PII만 잡는다. 이름·주소 같은 **비정형 PII**는 못 잡는다
  → 프로덕션은 NER(개체명 인식) 모델이나 Presidio 같은 전용 엔진을 결합해야 한다.
- 카드/전화 패턴은 **오탐(false positive)** 가능 (예: 13자리 일반 숫자열).
- 마스킹은 **로그 한정**이다. 지식베이스 원문(Article)은 검색 품질 때문에 원본 유지 —
  필요하면 ingest 단계에도 같은 서비스를 적용할 수 있다.

이 한계들은 숨긴 게 아니라 **의도적으로 문서화한 gap**이다. 프로덕션 버전은 각 항목을
보강한다.
