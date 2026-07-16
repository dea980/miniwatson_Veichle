# LLMOps 결정 — 추론 배치(Inference Placement): 온디바이스 vs 호스티드

> 상태: **결정 + 배포 반영**. 배경 추상화는 [LLM-ABSTRACTION.md](LLM-ABSTRACTION.md), 실행은 [DEPLOY-ORACLE.md](DEPLOY-ORACLE.md) §3. 서빙 벤치는 [SERVING.md](SERVING.md).

## 1. 문제 — $0 데모 박스에 8B를 자체호스팅하면 무너진다

배포 타겟은 **Oracle Always Free ARM(GPU 없음, CPU-only)**. 여기서 `qwen3:8b`(온디바이스 데모 기본)를 **자체호스팅하면 응답이 수십 초** — 데모로 못 쓴다. "스펙을 키우면 되지 않나?"는 오답이다. 무료 박스엔 GPU 옵션 자체가 없고, 유료 GPU는 데모의 전제($0)를 깬다.

**핵심 통찰**: 이건 "더 큰 하드웨어" 문제가 아니라 **"추론을 어디서 돌릴지(placement)"의 아키텍처 결정**이다. 그리고 그 결정은 이미 만들어 둔 `llm.provider` 추상화 덕분에 **코드 변경 0, 환경변수만**으로 바뀐다.

## 2. 결정 — 추론 배치를 "배포 프로파일"로 분리

| 구성요소 | 배치 | 이유 |
|---|---|---|
| **Chat 추론**(무거움) | **호스티드 OpenAI 호환 API**(Groq 권장) | GPU 필요·탄력적 부하 → 박스 밖 elastic 계층으로 분리 |
| **임베딩**(가벼움, granite-278m) | **박스 로컬 Ollama** | 278m은 CPU에서 충분히 빠름 + **재인덱싱 회피**(임베딩 모델 바뀌면 전체 벡터 재생성) |
| **앱 + pgvector** | **박스 로컬** | 상태/데이터 계층, 값싸고 고정 비용 |

즉 **"비싸고 탄력적인 추론"과 "값싸고 고정된 앱·데이터 계층"을 분리**한다. 이게 이 결정의 한 줄 요지다.

`VllmLlmClient`는 이름이 vLLM이지만 실제로는 **아무 OpenAI 호환 엔드포인트**에 붙는다 → Groq/OpenRouter/Gemini/자체 vLLM 모두 동일 코드.

### 설정 (코드 변경 없음)
```bash
LLM_PROVIDER=vllm
VLLM_URL=https://api.groq.com/openai        # → /v1/chat/completions 로 호출됨
VLLM_API_KEY=<groq_key>
VLLM_CHAT_MODEL=llama-3.3-70b-versatile
EMBEDDING_PROVIDER=ollama                    # 임베딩만 로컬 (chat provider와 분리)
```
- OpenRouter 변형: `VLLM_URL=https://openrouter.ai/api` (무료 모델 20+).
- `embedding.provider`가 `llm.provider`와 **분리**돼 있어(과거 결합 버그 수정) chat만 호스티드로 빼도 임베딩 빈이 정상 로딩된다.

## 3. "온디바이스 지원자가 왜 클라우드?" — 정면돌파

현대모비스 **온디바이스 AI Agent** 직무 맥락에서 이 결정은 서사와 충돌해 보일 수 있다. 오히려 **설계 역량으로 되받는 카드**다:

> 온디바이스가 목표지만 **$0 데모 인프라(GPU 없는 ARM)** 라는 제약에서 8B 온디바이스는 비현실적이다. 그래서 **추론 위치를 배포 프로파일로 분리**했다 — 데모=호스티드, 온프렘/차량 엣지=온디바이스. 코드는 `llm.provider` 하나로 동일하다. 핵심은 "온디바이스냐 아니냐"가 아니라 **"배포 타겟(클라우드 데모 / 차량 ECU / 온프렘)에 따라 추론 배치를 스왑할 수 있게 설계"** 했다는 것 — 이게 실제 온디바이스·엣지 제품에서 요구되는 능력이다.

## 4. 정직한 트레이드오프

| 트레이드오프 | 내용 | 완화 |
|---|---|---|
| **Rate limit** | Groq 무료 30 req/분 | 데모엔 충분, 프로덕션 부하엔 부족 → 유료/자체 vLLM 전환 |
| **데이터 이탈** | 질의가 호스티드로 나감 | 데모 데이터는 공개 NHTSA 매뉴얼이라 OK. **프로덕션은 데이터 주권상 온프렘/게이트웨이 전제** |
| **외부 의존** | 호스티드 장애 시 chat 중단 | **페일오버**: Groq + OpenRouter 2개 프로파일 |
| **온디바이스 서사** | 클라우드로 보임 | §3처럼 "배포 프로파일 분리"로 프레이밍 |

## 5. 데이터 주권 — 프로덕션 전환 기준

데모의 호스티드 chat은 **비용·속도 최적화**이지 최종 아키텍처가 아니다. 프로덕션(차량·딜러 데이터)에서는:
- **온프렘/H-Chat 게이트웨이 또는 차량 엣지 온디바이스**로 chat을 스왑 — `LLM_PROVIDER` 한 줄.
- 임베딩·pgvector는 이미 로컬이라 그대로.
- 이 "되돌릴 수 있는 결정" 성질이 [LLM-ABSTRACTION.md](LLM-ABSTRACTION.md)의 락인 회피 목표와 정확히 연결된다.

## 6. 면접 한 줄 (핵심 카드)

> "온디바이스 데모는 Ollama였지만 $0 ARM 박스엔 GPU가 없어 8B 자체호스팅은 수십 초. LLMOps 관점에서 **무거운 추론은 호스티드 OpenAI 호환(Groq)로 분리**하고 박스는 app+pgvector+경량 임베딩만 돌렸다. 코드 변경 0, `llm.provider` 환경변수만 — provider 추상화의 실익. 임베딩은 재인덱싱 때문에 로컬 유지(embedding.provider 분리). 프로덕션은 데이터 주권상 온프렘으로 스왑한다는 기준도 문서화했다."

## 7. 출처

- Free LLM APIs 2026 비교(OpenRouter, OpenAI 호환, 무료 모델 20+): https://openrouter.ai/blog/tutorials/free-llm-apis-compared/
- Groq Free Tier 2026(OpenAI 호환, 카드 불필요, Llama 3.3 70B ~320 tok/s): https://www.getaiperks.com/en/ai/groq-free-tier-2026
- Best Free LLM API Tiers 2026(Groq/Cerebras/GitHub Models): https://wetheflywheel.com/en/ai-model-access/free-llm-api-tiers-2026/
