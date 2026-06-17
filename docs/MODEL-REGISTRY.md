# 모델 버전 레지스트리 (Phase 3)

응답이 "어떤 모델/설정으로" 생성됐는지 추적한다. 같은 질문이어도 provider, 임베딩 모델, rerank 전략, 앱 버전이 다르면 답이 달라지므로, 그 구성을 응답마다 기록해 재현성, 드리프트 귀인, A/B 비교의 근거로 삼는다.

거버넌스/감사([SECURITY.md], query_log)와 모니터링([MONITORING.md])을 잇는 마지막 조각이다.

## 1. 무엇을 기록하나

| 축 | 어디에 | 비고 |
|---|---|---|
| 실제 생성 모델(per-call) | `query_log.model` | 이미 있음. 호출마다 실제 쓰인 모델(granite4/gemma4 등) |
| 구성 지문(provider, embed, rerank, ver) | `query_log.modelConfig` (신규) | `ModelRegistry.fingerprint()`로 audit 시 박음 |

`model`(실제 모델)과 `modelConfig`(주변 구성)를 나눠, 행 하나로 "이 답은 X 모델 + Y 구성에서 나왔다"가 완성된다.

## 2. 구성

- **`governance/ModelRegistry`** — 활성 설정에서 지문을 만든다.
  - `fingerprint()` → `provider=ollama;embed=granite-embedding:278m;rerank=mmr;ver=dev` (사람이 읽음)
  - `hash()` → 짧은 sha256 (같은 구성 그룹핑/비교용)
  - 소스: `llm.provider`, `ollama.embed-model`, `rerank.strategy`, `app.version`(CI가 `APP_VERSION`=git sha 주입, 없으면 dev)
- **`OllamaService.audit()`** — 호출마다 `log.setModelConfig(modelRegistry.fingerprint())`. 추론 단일 choke point라 누락 없음.
- **`GET /api/governance/model-version`** — 현재 지문/해시 반환. "지금 이 서비스가 어떤 구성으로 답하나"를 즉시 확인.

## 3. 왜 이렇게 (설계)

- **choke point에서 한 번.** model 메트릭/감사와 같은 자리(audit)에서 박아, 새 엔드포인트가 생겨도 빠지지 않는다.
- **per-call 모델과 구성을 분리.** 모델은 요청마다 바뀔 수 있어 행 컬럼(`model`)으로, 구성은 배포 단위라 지문(`modelConfig`)으로. 합치면 완전한 출처.
- **해시로 그룹핑.** 구성이 바뀌면 해시가 바뀌어, "언제부터 구성이 달라졌고 그 전후 품질(feedback/recall)이 어떻게 변했나"를 추적 가능(드리프트 귀인).
- **rerank/hybrid의 per-request 오버라이드**는 eval 전용(T2, 운영 off)이라 지문엔 기본 전략만 담는다. 운영에서 외부가 전략을 못 바꾸므로 지문=실제 구성.

## 4. 활용 예

- 재현: 특정 답이 이상 → `modelConfig` 보고 그 구성으로 재현.
- 드리프트: `modelConfig` 해시별로 feedback down 비율 비교 → 어떤 구성 변화가 품질을 떨어뜨렸는지.
- A/B: 두 구성(예: rerank=mmr vs none)을 기간 나눠 돌리고 해시별 지표 비교.

## 5. 한계와 다음

- 앱 버전은 `APP_VERSION` 주입에 의존(미주입 시 dev). CI에서 `APP_VERSION=${{ github.sha }}` 주입 권장([CICD.md]).
- 토큰 사용량/비용은 아직 미기록. 관리형 제공자로 가면 LlmClient 구현체에서 토큰을 받아 `query_log`와 메트릭에 추가(MONITORING.md 4절과 연계).

## 6. 검증

```bash
# 현재 구성 지문
curl -s localhost:8080/api/governance/model-version | python3 -m json.tool

# 호출 후 로그에 modelConfig가 박히는지
curl -s -X POST localhost:8080/api/rag/ask -H 'Content-Type: application/json' -d '{"question":"test"}' >/dev/null
curl -s localhost:8080/api/governance/logs | python3 -m json.tool | grep -A1 modelConfig | head
```
`modelConfig`에 `provider=...;embed=...;rerank=...;ver=...`가 보이면 통과.
