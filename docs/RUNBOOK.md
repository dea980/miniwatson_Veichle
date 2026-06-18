# 실행 & 검증 런북 (따라 하기)

이번 작업분(HotSpot 전환, LLM 추상화, 보안 배포, 운영 모니터링, 모델 레지스트리)을 직접 돌리고 확인하는 순서. 각 단계 "명령 → 기대 결과".

선행: Ollama가 떠 있어야 함.
```bash
ollama serve &                       # 별도 터미널이어도 됨
ollama pull ibm/granite4:latest
ollama pull granite-embedding:278m
ollama pull llava:latest
```

---

## 0. 빌드 (HotSpot 런타임 확인)

```bash
cd ~/Desktop/miniwatson
java -version          # "OpenJDK 64-Bit Server VM" 떠야 함. "OpenJ9" 보이면 Temurin으로 교체 (HOTSPOT-RUNTIME.md)
./mvnw -q -DskipTests compile        # 무출력 = 성공
```
기대: 컴파일 그린. (런타임/CI 둘 다 Temurin = 배포와 일치)

---

## 1. 앱 기동 + 크래시 안 나는지

```bash
./mvnw spring-boot:run               # dev 프로파일, H2 인메모리
```
기대: `Started MiniwatsonApplication`. 요청 돌려도 `javacore.*` 덤프 안 생김(OpenJ9 크래시 해결).

---

## 2. LLM 추상화 회귀 확인 (eval)

다른 터미널에서:
```bash
bash eval/ingest_corpus.sh           # 골든 코퍼스 적재. 끝에 index stats count > 0
python3 eval/run_eval.py             # retrieval recall 스윕(38케이스)
```
기대: recall이 추상화 전과 동일(none/mmr 100% 기준). 거버넌스 살아있는지:
```bash
curl -s localhost:8080/api/governance/logs | python3 -m json.tool | head
```
→ 호출이 기록되면 OllamaService(거버넌스 래퍼) 정상.

---

## 3. 모니터링 (Prometheus + Grafana)

앱은 위에서 mvnw로 떠 있는 채로, 다른 터미널:
```bash
docker compose -f docker-compose.monitoring.yml up -d
```
접속:
- 앱 UI: http://localhost:8080
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000  (admin / `.env`의 GRAFANA_PASSWORD)

메트릭 생성(첫 호출 때 lazy 등록):
```bash
curl -s -X POST localhost:8080/api/rag/ask -H 'Content-Type: application/json' -d '{"question":"test"}' >/dev/null
curl -s localhost:8080/actuator/prometheus | grep miniwatson_llm   # calls/latency 시리즈 보이면 OK
```
Grafana 사용:
1. 로그인 → 데이터소스(Prometheus) 자동 연결 확인.
2. Explore(나침반) → PromQL:
   - p95: `histogram_quantile(0.95, sum(rate(miniwatson_llm_latency_seconds_bucket[5m])) by (le, model))`
   - 분당 호출: `sum(rate(miniwatson_llm_calls_total[1m])) by (type)`
3. 기성 대시보드: Dashboards → Import → `4701`(JVM) → Prometheus 선택.

내릴 때: `docker compose -f docker-compose.monitoring.yml down`

---

## 4. 모델 버전 레지스트리

```bash
curl -s localhost:8080/api/governance/model-version | python3 -m json.tool
# → {"fingerprint":"provider=ollama;embed=granite-embedding:278m;rerank=mmr;ver=dev","hash":"..."}

curl -s localhost:8080/api/governance/logs | python3 -m json.tool | grep modelConfig | head
# → 각 로그에 modelConfig 지문이 박혀 있으면 통과
```

---

## 5. 보안 (배포 전 점검)

지금 로컬은 `SECURITY_ENABLED=false`(UI 편의). 배포 리허설로 인증 켜보려면:
```bash
SECURITY_ENABLED=true ./mvnw spring-boot:run
# 키 없이 호출 → 401
curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8080/api/rag/ask -H 'Content-Type: application/json' -d '{"question":"x"}'
```
기대: 401(fail-closed). 키 주입/테넌트 격리는 SECURITY.md 3절과 7절.

---

## 6. 풀스택 (선택, 배포 리허설)

`.env`는 이미 생성됨(DB_PASSWORD/GRAFANA_PASSWORD). 앱+DB+Ollama+모니터링 전부 컨테이너:
```bash
docker compose -f docker-compose.prod.yml up -d
```
주의: app은 GHCR 이미지를 pull → 네 최신 코드 반영하려면 먼저 push해서 CI가 새 이미지 빌드(public)해야 함. 로컬 코드로 바로 띄우려면 prod compose의 app을 `build: .`로 바꾸면 됨(요청 시 해줄게).

---

## 7. 커밋 & CI

```bash
git add -A
git commit -m "feat: 모니터링(Prometheus/Grafana) + 모델 레지스트리 + .env 템플릿"
git push origin main                 # GitHub Actions: test 게이트 → GHCR 멀티아치 이미지
```
확인: GitHub → Actions 탭에서 test 녹색, build-image가 GHCR 푸시. (GitLab은 패스)

---

## 참고 문서

- 런타임: [HOTSPOT-RUNTIME.md](HOTSPOT-RUNTIME.md)
- 추상화: [LLM-ABSTRACTION.md](LLM-ABSTRACTION.md)
- 배포: [CLOUD-DEPLOYMENT.md](CLOUD-DEPLOYMENT.md)
- CI/CD: [CICD.md](CICD.md)
- 모니터링: [MONITORING.md](MONITORING.md)
- 모델 레지스트리: [MODEL-REGISTRY.md](MODEL-REGISTRY.md)
- 보안: [SECURITY.md](SECURITY.md)
