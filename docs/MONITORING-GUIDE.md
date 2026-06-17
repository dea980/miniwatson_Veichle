# Grafana 점검 가이드 (무엇을, 어떤 기준으로 보나)

셋업은 [MONITORING.md](MONITORING.md). 이 문서는 "대시보드에서 무엇을 보고, 정상/이상을 어떻게 가르고, 이상이면 뭘 하나"의 운영 기준이다. 핵심 전제: **이 서비스의 병목은 LLM 추론 한 곳**이고, CPU Ollama라 한 호출이 수 초 걸린다(실측 약 5초). 그래서 지연과 동시성 신호가 1순위다.

## 1. 1순위 — LLM (서비스의 심장)

| 지표 | PromQL | 정상 | 이상 신호 → 원인·조치 |
|---|---|---|---|
| 추론 p95 지연 | `histogram_quantile(0.95, sum(rate(miniwatson_llm_latency_seconds_bucket[5m])) by (le, model))` | CPU 기준 수 초(모델별 일정) | 평소의 2배 이상 급증 → 동시 호출 경합/모델 교체/Ollama 과부하. GPU 또는 관리형 추론 검토 |
| 처리량 | `sum(rate(miniwatson_llm_calls_total[1m])) by (type)` | 트래픽에 비례 | 0인데 사용자 있음 → 앱↔Ollama 끊김. 급증 → 동시성 가드 확인 |
| 모델 분포 | `sum(miniwatson_llm_calls_total) by (model)` | 의도한 모델 위주 | 예상 밖 모델 비중 → 잘못된 기본값/외부가 모델 바꿈 |
| PII 마스킹률 | `sum(rate(miniwatson_pii_redacted_total[5m]))` | 코퍼스 특성에 맞게 | 급증 → 민감 데이터 유입(데이터 경계 점검, DEEP-DIVE-DATA-GOVERNANCE.md) |

p95가 핵심이다. 평균은 느린 호출을 숨긴다. 데모 전엔 p95가 "사람이 기다릴 만한가"로 본다.

## 2. 동시성/포화 (병목이 터지는 자리)

| 지표 | PromQL | 정상 | 이상 → 조치 |
|---|---|---|---|
| Tomcat 스레드 사용 | `tomcat_threads_busy_threads / tomcat_threads_config_max_threads` | < 0.7 | 1에 근접 → 요청이 줄 서는 중. LLM 지연이 스레드를 잡아먹는 것. 동시 처리 제한/타임아웃 가드 확인 |
| DB 커넥션 대기 | `hikaricp_connections_pending` | 0 | > 0 지속 → 풀 고갈. 쿼리 지연 또는 풀 크기 부족 |
| DB 활성 커넥션 | `hikaricp_connections_active` | 풀 max 미만 | max 붙어있음 → 위와 동일 |

`tomcat_threads_*`가 안 보이면 `application.yaml`에 `server.tomcat.mbeanregistry.enabled: true` 추가. 이 두 줄이 앞서 논의한 "동시성 병목"을 수치로 보여주는 자리다.

## 3. 에러/안정성

| 지표 | PromQL | 정상 | 이상 → 조치 |
|---|---|---|---|
| 5xx 에러율 | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m]))` | < 1% | 상승 → 로그 확인. 과거 H2 감사저장 실패가 500 낸 적 있음(now fail-open) |
| 엔드포인트별 지연 | `histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le, uri))` | 안정 | 특정 uri만 급증 → 그 경로 조사 |
| 앱 가동 | `up{job=~"miniwatson.*"}` | 1 | 0 → 앱 다운. 재기동/로그 |

## 4. JVM/리소스 (HotSpot)

| 지표 | PromQL | 정상 | 이상 → 조치 |
|---|---|---|---|
| 힙 사용률 | `sum(jvm_memory_used_bytes{area="heap"}) / sum(jvm_memory_max_bytes{area="heap"})` | < 0.8 | > 0.85 지속 → OOM 위험. `-Xmx` 작은 박스라 특히 주의(CLOUD-DEPLOYMENT) |
| GC 일시정지 | `rate(jvm_gc_pause_seconds_sum[5m])` | 낮음 | 급증 → 메모리 압박, 지연 유발 |
| CPU | `system_cpu_usage`, `process_cpu_usage` | 여유 | 1에 근접 → Ollama 추론이 CPU 점유(예상된 동작이나 동시성 한계) |

## 5. 품질/거버넌스 (드리프트)

| 지표 | 어디서 | 보는 법 | 이상 → 조치 |
|---|---|---|---|
| feedback down 비율 | `/api/governance/stats` feedback 집계 | down/(up+down) 추세 | 상승 → 응답 품질 저하. `modelConfig`로 어떤 구성 변화인지 귀인 |
| 구성 지문 변화 | `query_log.modelConfig` / `/api/governance/model-version` | 해시가 바뀐 시점 | 변경 전후 품질 비교(MODEL-REGISTRY.md) |
| 오프라인 recall | `eval/run_eval.py` | 배포 전/정기 | 임계 미달 → 배포 보류(CICD 갭: eval 게이트) |

품질은 메트릭만으론 안 보인다. feedback(온라인) + eval(오프라인) + modelConfig(귀인) 3개를 같이 본다.

## 6. 점검 루틴

데모 직전(5분):
- p95 지연이 기다릴 만한가, `up`=1, 5xx≈0, 힙 < 80%, Ollama 모델 로드됨.

정기(주간):
- 처리량/지연 추세, feedback down 추세, 힙·GC 추세, `modelConfig` 변경 이력.

## 7. 알람 임계 (Grafana Alerting 제안)

| 조건 | 임계 | 의미 |
|---|---|---|
| `up == 0` (1분) | 즉시 | 서비스 다운 |
| 5xx 비율 > 5% (5분) | 경고 | 장애 진행 |
| 힙 사용률 > 0.9 (5분) | 경고 | OOM 임박 |
| LLM p95 > 평소 2배 (10분) | 정보 | 추론 포화/드리프트 |
| `hikaricp_connections_pending` > 0 (5분) | 경고 | DB 풀 고갈 |

알람은 적게, 행동 가능한 것만. 노이즈가 많으면 다 무시하게 된다.

## 8. 이상 시 1차 동선

1. `up`/로그로 살아있나 → 죽었으면 재기동(OPERATIONS.md 런북).
2. 지연 급증이면 LLM부터: Ollama 살아있나, 동시 호출 몰렸나, 모델 바뀌었나(`model-version`).
3. 5xx면 최근 배포/감사저장/DB 확인.
4. 힙/GC면 부하 줄이거나 박스 키우기, `-Xmx` 점검.
5. 품질 저하면 `modelConfig` 해시로 구성 변화 귀인 후 eval 재측정.
