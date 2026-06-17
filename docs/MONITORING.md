# 운영 모니터링 (Phase 2)

지연, 처리량, 검색/응답 품질, 비용을 운영 중에 추적하기 위한 메트릭 스택. Actuator + Micrometer로 메트릭을 노출하고 Prometheus가 수집, Grafana로 시각화한다. 거버넌스 감사(query_log)와 보완 관계다 (감사 = 행 단위 추적, 메트릭 = 시계열 집계).

배포 맥락은 [CLOUD-DEPLOYMENT.md](CLOUD-DEPLOYMENT.md), 보안은 [SECURITY.md](SECURITY.md).

## 1. 구성

```
app(/actuator/prometheus) ──scrape──> Prometheus ──query──> Grafana
        │ Micrometer 메트릭
        └ 커스텀: miniwatson.llm.* (OllamaService.audit에서 기록)
```

- **Actuator + micrometer-registry-prometheus** (pom) — `/actuator/prometheus`로 메트릭 노출. JVM/HTTP/GC 표준 메트릭 자동 포함.
- **Prometheus** (compose) — 15s 간격으로 `app:8080/actuator/prometheus` 스크레이프.
- **Grafana** (compose, :3000) — Prometheus 데이터소스 자동 프로비저닝.

## 2. 커스텀 메트릭 (LLM)

`OllamaService.audit()`(거버넌스 래퍼)에서 호출마다 기록한다. 추론 경로의 단일 choke point라 모든 LLM 호출이 빠짐없이 잡힌다.

| 메트릭 | 타입 | 태그 | 의미 |
|---|---|---|---|
| `miniwatson.llm.latency` | Timer(히스토그램) | model, type(chat/vision) | 추론 지연. p50/p95/p99 |
| `miniwatson.llm.calls` | Counter | model, type | 호출수(처리량, 모델 분포) |
| `miniwatson.pii.redacted` | Counter | type | PII 마스킹 건수(거버넌스 신호) |

히스토그램 버킷은 `application.yaml`의 `management.metrics.distribution.percentiles-histogram`으로 켠다.

## 3. PromQL 예시

```promql
# p95 지연 (모델별, 5분 창)
histogram_quantile(0.95, sum(rate(miniwatson_llm_latency_seconds_bucket[5m])) by (le, model))

# 분당 호출수 (유형별)
sum(rate(miniwatson_llm_calls_total[1m])) by (type)

# 에러율 (HTTP 5xx 비율)
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m]))

# PII 마스킹 추세
sum(rate(miniwatson_pii_redacted_total[5m]))
```

(Micrometer가 `.`을 `_`로, Timer를 `_seconds`로 바꿔 노출한다.)

## 4. 검색/응답 품질과 드리프트

시스템 지연뿐 아니라 "답이 나빠지는지"를 본다.

- **온라인 신호**: query_log의 `feedback`(up/down)을 시계열로. down 비율 상승 = 품질 드리프트 의심. `/api/governance/stats`의 feedback 집계를 메트릭으로 승격하면 Grafana에서 추세로 본다(후속).
- **오프라인 게이트**: golden.json recall은 [EVALUATION.md](EVALUATION.md)의 eval로 측정. CI에 eval 게이트를 넣으면 배포 전 회귀 차단([CICD.md] 갭 참고).
- **비용**: Ollama 자체호스팅은 토큰비 0이지만, 관리형(watsonx/Bedrock 등)으로 교체하면 토큰 사용량을 메트릭에 추가해 비용을 추적해야 한다(LlmClient 구현체에서 토큰 카운트 기록 → `miniwatson.llm.tokens`).

## 5. Grafana 대시보드

데이터소스는 자동 연결된다. 대시보드는:

- **시스템/JVM**: 커뮤니티 대시보드 import 권장 — JVM(Micrometer) ID `4701`, Spring Boot Statistics 등. Grafana → Dashboards → Import → ID 입력.
- **LLM 커스텀**: 위 PromQL로 패널 직접 추가(p95 지연, 분당 호출, PII 추세, 에러율). 안정화되면 JSON으로 박아 프로비저닝에 추가.

## 6. 보안 (중요)

`/actuator/**`는 `/api/**`가 아니라서 ApiKeyAuthFilter를 타지 않는다 → 인증 없이 열린다. compose에서 app의 8080을 외부 공개하므로 **운영에선 리버스 프록시에서 `/actuator`를 외부 차단**하고, Prometheus는 내부 네트워크에서만 스크레이프하게 둔다. Grafana는 `GF_SECURITY_ADMIN_PASSWORD`를 `.env`로 바꾼다(기본 admin 금지). 근거는 [SECURITY.md](SECURITY.md) 7절.

## 7. 검증

```bash
# 메트릭 노출 확인
curl -s localhost:8080/actuator/prometheus | grep miniwatson_llm
# 호출 몇 번 발생 후 latency/calls 시리즈가 보이면 통과

# Prometheus 타깃 UP (compose 기동 후)
#   http://<host>:9090/targets (publish 시) 또는 컨테이너 내부에서 확인
# Grafana
#   http://<host>:3000  (admin / GRAFANA_PASSWORD) → 데이터소스 Prometheus 연결 확인
```
