# LLM 추론 추상화 설계 (벤더 중립)

추론 제공자(Ollama / watsonx / Bedrock / Vertex)를 "코드가 묶이는 결정"이 아니라 "설정값"으로 만들기 위한 인터페이스 설계. 이 추상화가 끝나야 클라우드 선택이 되돌릴 수 있는 결정이 된다(락인 회피). 배포 맥락은 [CLOUD-DEPLOYMENT.md](CLOUD-DEPLOYMENT.md).

설계 원칙: 제공자 교체는 설정 한 줄, 거버넌스(PII/감사)는 제공자와 무관하게 한곳에서, 기존 동작은 회귀 0.

---

## 1. 현재 구조 (출발점)

| 관심사 | 클래스 | 메서드 | 비고 |
|---|---|---|---|
| 생성/비전 | `service/OllamaService` (159줄) | `ask(question)`, `ask(prompt,model)`, `ask(prompt,model,userQuestion)`, `ask(prompt,model,userQuestion,sources)`, `askWithImages(prompt,visionModel,base64Images)`, `availableModels()`, `defaultModel()` | RestTemplate로 Ollama HTTP 직접 호출 |
| 임베딩 | `service/EmbeddingService` | `embed(String) → List<Float>` | 별도 |
| 거버넌스 | OllamaService 내부 | `PiiRedactionService`, `QueryLogRepository`, `lastQueryLogId()` | 추론과 섞여 있음 |

호출처(교체 영향 범위, 7곳): `RagService`, `IngestionService`, `TextToSqlService`, `LlmReranker`, `MultimodalController`, `RagController`, `DataController`.

문제: 제공자를 바꾸려면 OllamaService를 갈아엎어야 하고, 거버넌스 로직이 거기 묶여 있어 같이 휘말린다.

---

## 2. 목표 구조

```
호출처 7곳 ──> LlmClient (인터페이스)
                  └─ GovernanceLlmClient (데코레이터: PII 리댁션 + QueryLog)
                       └─ OllamaLlmClient | WatsonxLlmClient | BedrockLlmClient | ...  (provider별)

호출처 ──> EmbeddingClient (인터페이스)
              └─ OllamaEmbeddingClient | WatsonxEmbeddingClient | ...
```

핵심 결정: **거버넌스를 데코레이터로 분리.** PII 리댁션과 감사 로그는 어떤 제공자를 쓰든 동일하게 적용돼야 하므로, provider 구현체는 순수 추론만 하고 거버넌스는 그 바깥을 감싼다. 이러면 제공자를 늘려도 거버넌스를 중복 구현하지 않는다.

---

## 3. 인터페이스 (제안 시그니처)

기존 호출처가 쓰는 메서드를 그대로 인터페이스로 올린다(호출부 변경 최소화).

```java
public interface LlmClient {
    String ask(String prompt, String model, String userQuestion, String sources);
    String askWithImages(String prompt, String visionModel, List<String> base64Images);
    List<String> availableModels();
    String defaultModel();
}

public interface EmbeddingClient {
    List<Float> embed(String text);
    int dimension();   // 제공자/모델별 차원(768 등) 노출 — pgvector 정합성 점검용
}
```

`ask`의 짧은 오버로드(`ask(question)` 등)는 인터페이스에 두지 말고 호출처 또는 default 메서드로 위임해 인터페이스를 좁게 유지한다. `dimension()`을 둬서 임베딩 차원 변경(제공자 교체 시)을 코드가 인지하게 한다.

---

## 4. 제공자 선택 (설정 전환)

`application.yaml`에 스위치:

```yaml
llm:
  provider: ${LLM_PROVIDER:ollama}   # ollama | watsonx | bedrock | vertex
```

Spring에서 구현체를 조건부 등록:

```java
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "ollama", matchIfMissing = true)
public class OllamaLlmClient implements LlmClient { ... }
```

거버넌스 데코레이터는 `@Primary`로 감싸 주입 지점이 항상 거버넌스 경유가 되게 한다.

---

## 5. 마이그레이션 순서 (왜 / 작업 / 검증)

### Step 1 — 인터페이스 추출
**왜**: 호출처가 구현이 아니라 계약에 의존하게.
**작업**: `LlmClient`/`EmbeddingClient` 인터페이스 정의. 현재 `OllamaService`를 `OllamaLlmClient implements LlmClient`로, `EmbeddingService`를 `OllamaEmbeddingClient implements EmbeddingClient`로 정리(동작 그대로).
**검증**: 컴파일 + 기존 단위테스트 그린.

### Step 2 — 거버넌스 분리
**왜**: PII/감사를 제공자와 분리해 중복 제거.
**작업**: PII 리댁션 + QueryLog 호출을 `GovernanceLlmClient`(데코레이터)로 이동. provider 구현체는 순수 추론만.
**검증**: `/api/governance/logs`에 호출이 그대로 남는지, `lastQueryLogId` 연계 유지.

### Step 3 — 호출처를 인터페이스로 전환
**왜**: 7곳이 인터페이스 타입을 주입받게.
**작업**: `RagService`, `IngestionService`, `TextToSqlService`, `LlmReranker`, 컨트롤러 3곳의 주입 타입을 `OllamaService` → `LlmClient`/`EmbeddingClient`로 교체.
**검증**: 컴파일 + 앱 기동.

### Step 4 — 회귀 확인 (provider=ollama)
**왜**: 추상화가 동작을 바꾸지 않았음을 수치로.
**작업**: `LLM_PROVIDER=ollama`로 로컬 기동.
**검증**:
```bash
python3 eval/run_eval.py        # recall이 추상화 전과 동일해야 함(회귀 0)
curl -s -X POST localhost:8080/api/rag/ask -d '{"question":"What is RAG?"}' -H 'Content-Type: application/json'
```
[EVALUATION.md](EVALUATION.md) golden set recall 불변이면 추상화 완료.

### Step 5 — (배포 시) 제공자 구현체 추가
제공자를 고른 시점에 `WatsonxLlmClient` 등만 추가하고 `LLM_PROVIDER`만 바꾼다. 호출처와 거버넌스는 손대지 않는다. 임베딩 차원이 768로 유지되면 pgvector 재인덱싱 불필요(`dimension()`로 점검), 달라지면 재인덱싱.

---

## 6. 이 설계가 주는 것

- 클라우드/제공자 교체 = 설정 한 줄 + 구현체 한 개 추가. 문서/호출처 안 갈아엎음.
- 거버넌스(PII/감사)가 제공자와 독립 → 데이터 경계 정책을 한곳에서 관리(거버넌스 일관성).
- `dimension()`으로 임베딩 차원 불일치를 배포 전에 잡음.
- 면접 포인트: "벤더 락인 회피 + 거버넌스 분리"를 코드 구조로 보여줌.

---

다음: 이 추상화가 끝나면 제공자/호스트를 골라 [CLOUD-DEPLOYMENT.md](CLOUD-DEPLOYMENT.md) 6번 배포 단계로 간다.
