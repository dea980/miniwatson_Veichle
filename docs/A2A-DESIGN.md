# A2A (Agent2Agent) 설계 — 진단 to 견적 위임

> 요지: 진단 에이전트가 부품 견적을 스스로 하지 않고, 별도 견적 에이전트에게 A2A 프로토콜로 위임한다. 카드로 능력을 발견하고 message/send로 태스크를 넘긴다. 지금은 한 앱 안이지만 A2A 와이어 계약을 지켜, 견적 에이전트를 별도 서비스로 떼어내도 진단 에이전트 코드는 안 바뀐다.

## 0. 왜 A2A인가 (스코프)

MCP는 "호스트가 내 도구를 부른다"(도구 접근)이고, A2A는 "에이전트끼리 동료로 위임한다"(피어 협업)다. 서로 다른 층위다. 단일 서비스에서 A2A를 억지로 넣으면 과장이 되므로, 진짜 두 번째 에이전트가 필요한 지점에만 붙였다: 진단이 부품을 지목하면 견적을 견적 에이전트에게 넘긴다. 이때 비로소 프로토콜이 필요한 이유가 생긴다.

바인딩은 A2A 3종(JSON-RPC, gRPC, REST) 중 REST/JSON-RPC를 택했다(앱이 이미 HTTP).

## 1. 두 에이전트

- 진단 에이전트: 기존 AgentService(RAG + SQL 오케스트레이션). A2A 클라이언트 역할.
- 견적 에이전트: 기존 EstimateService를 A2A 서버로 감쌈. 부품 견적/재고 산정.

## 2. 견적 에이전트 = A2A 서버 (A2aController)

- 에이전트 카드: `GET /.well-known/agent-card.json`(무인증). name, description, url, capabilities, skills(parts_estimate)를 광고. 능력 발견용.
- 태스크 엔드포인트: `POST /a2a`, JSON-RPC 2.0 `message/send`. 메시지의 data part에서 problem/car/model을 읽어 EstimateService.estimate 호출, 결과를 agent 역할 메시지의 data part로 반환.

## 3. 진단 에이전트 = A2A 클라이언트 (A2aClient, A2aDemoController)

- A2aClient.delegateViaCard(base, payload): (1) `base + /.well-known/agent-card.json`으로 카드를 가져와 엔드포인트(url)를 발견, (2) 그 엔드포인트에 `message/send`로 payload를 data part에 담아 전송, (3) 응답의 data part를 추출.
- A2aDemoController `POST /api/a2a/diagnose-estimate`: 진단(AgentService.run) 후, 같은 문제를 견적 에이전트에게 A2A로 위임. `{diagnosis, estimate_via_a2a}` 반환.

흐름: 사용자 질문 to 진단(RAG/SQL) to A2A 카드 발견 to message/send to 견적 to 통합 응답.

## 4. 정직한 프레이밍과 한계

- 한 앱 안: 두 에이전트가 같은 프로세스에 있지만, A2A 와이어(카드 + JSON-RPC message/send)를 지킨다. 견적 에이전트를 별도 서비스/URL로 옮겨도 진단 에이전트는 base URL만 바꾸면 된다. 그게 A2A의 요점(불투명한 에이전트 간 상호운용).
- 동기 only: message/send만 구현. 스트리밍(message/stream, SSE)과 tasks/get(장기 태스크)은 미구현. 견적은 즉시 반환이라 동기로 충분.
- 견적 매칭 한계: EstimateService가 자유텍스트 문제를 부품 카탈로그에 매칭하는 정확도가 낮아 items가 빌 수 있다(프로토콜과 별개). 부품 키워드가 분명한 입력이나 매칭 개선이 필요.
- 보안: `/.well-known/*`와 `/a2a`는 무인증(카드는 원래 무인증). 프로덕션이면 A2A 인증(카드의 securitySchemes)로 확장.

## 5. JD 연결 (에이전트 프로토콜)

"진단 에이전트가 부품 견적을 직접 하지 않고, 능력이 다른 견적 에이전트에게 A2A로 위임하게 했다. 에이전트 카드로 능력을 발견하고 JSON-RPC message/send로 태스크를 넘긴다. 단일 앱이지만 와이어 계약을 지켜, 견적을 별도 서비스로 분리해도 진단 코드는 불변이다."

MCP(도구 노출)와 함께, 우대사항의 'A2A, MCP 등 에이전트 프로토콜 활용'을 두 층위(도구 접근 + 피어 위임)로 실증한다.

## 6. 검증

```
curl -s http://localhost:8080/.well-known/agent-card.json
curl -s -X POST http://localhost:8080/api/a2a/diagnose-estimate -H 'Content-Type: application/json' \
  -d '{"question":"브레이크 패드 교체","car":"PALISADE","model":"palisade"}'
```
→ `{diagnosis:{tool,answer}, estimate_via_a2a:{...}}`. 진단은 RAG, 견적은 A2A 위임 결과.
