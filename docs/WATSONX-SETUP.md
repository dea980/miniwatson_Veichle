# IBM Cloud / watsonx.ai 세팅 (.env 3개 값 뽑기)

목표: `WATSONX_API_KEY`, `WATSONX_PROJECT_ID`, `WATSONX_URL` 세 개를 얻어 `.env`에 넣고 `LLM_PROVIDER=watsonx`로 전환. 코드는 [WatsonxLlmClient]/[WatsonxEmbeddingClient]가 이미 준비됨([LLM-ABSTRACTION.md]).

콘솔 UI 라벨은 시점에 따라 조금 다를 수 있다. 흐름 기준으로 따라가면 된다.

## 1. IBM Cloud 계정

- https://cloud.ibm.com 가입. watsonx.ai **Lite는 무료 계정에서 프로비저닝 가능**.
- $200 크레딧은 Pay-As-You-Go(카드) 등록 시 적용되며 **30일 한정**. Lite만 쓸 거면 PAYG 없이도 됨. 과금 락을 원하면 **PAYG를 켜지 마라**(Lite 한도 초과 시 과금 대신 막힘).

## 2. watsonx.ai 프로비저닝

- IBM Cloud 카탈로그에서 **watsonx.ai** 검색 → 생성. 또는 https://dataplatform.cloud.ibm.com 에서 watsonx 시작.
- 이때 **watsonx.ai Studio + Runtime(Lite) + Cloud Object Storage(Lite)** 가 함께 생성된다(COS는 의존 서비스라 필수).
- **리전 주의**: 생성 리전이 곧 API URL을 정한다.

| 리전 | WATSONX_URL |
|---|---|
| us-south(달라스) | https://us-south.ml.cloud.ibm.com |
| eu-de(프랑크푸르트) | https://eu-de.ml.cloud.ibm.com |
| eu-gb(런던) | https://eu-gb.ml.cloud.ibm.com |
| jp-tok(도쿄) | https://jp-tok.ml.cloud.ibm.com |
| au-syd(시드니) | https://au-syd.ml.cloud.ibm.com |

## 3. 프로젝트 생성 + Project ID

- watsonx 콘솔 → **Projects → New project**(샌드박스 프로젝트 자동 생성돼 있으면 그걸 써도 됨).
- 프로젝트 진입 → **Manage → General → Project ID** 복사 → `WATSONX_PROJECT_ID`.
- **Manage → Services & integrations → Associate service → watsonx.ai Runtime** 으로 런타임을 프로젝트에 연결(안 하면 추론 호출이 권한 오류).

## 4. API 키 생성

- IBM Cloud(콘솔 우상단) → **Manage → Access (IAM) → API keys → Create**.
- 생성된 키는 **한 번만 표시**되니 즉시 복사 → `WATSONX_API_KEY`. (이게 IAM 토큰 교환용 플랫폼 키)

## 5. 모델 확인

- watsonx **Prompt Lab**에서 사용 가능한 foundation model 목록 확인. granite 계열(예: `ibm/granite-3-8b-instruct`)이 그 리전에 있는지 보고 `WATSONX_MODEL`에 맞춘다.
- 임베딩은 `ibm/granite-embedding-278m-multilingual`(768차원, 현재와 동일).

## 6. .env 작성

```bash
LLM_PROVIDER=watsonx
WATSONX_URL=https://us-south.ml.cloud.ibm.com     # 2번 리전에 맞게
WATSONX_API_KEY=<4번 키>
WATSONX_PROJECT_ID=<3번 프로젝트 id>
WATSONX_MODEL=ibm/granite-3-8b-instruct
WATSONX_EMBED_MODEL=ibm/granite-embedding-278m-multilingual
```

## 7. 키 검증 (앱 없이 먼저)

```bash
# IAM 토큰 발급되는지
TOKEN=$(curl -s -X POST https://iam.cloud.ibm.com/identity/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "grant_type=urn:ibm:params:oauth:grant-type:apikey&apikey=$WATSONX_API_KEY" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
echo "${TOKEN:0:20}..."   # 토큰 앞부분 보이면 키 OK

# 생성 호출 테스트
curl -s -X POST "$WATSONX_URL/ml/v1/text/generation?version=2024-05-01" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"model_id":"ibm/granite-3-8b-instruct","input":"Say hi in one word","project_id":"'"$WATSONX_PROJECT_ID"'","parameters":{"max_new_tokens":10}}'
# results[0].generated_text 가 오면 프로젝트 연결까지 정상
```

권한 오류(403)면 3번의 Runtime 서비스 연결 누락. 모델 오류면 5번 리전별 모델명 확인.

## 8. 앱 전환 + 검증

```bash
# .env 반영 후 재기동
curl -s localhost:8080/api/governance/model-version   # provider=watsonx 확인
bash eval/ingest_corpus.sh                              # watsonx 임베딩으로 재적재(차원 768 유지)
python3 eval/run_eval.py                                # recall 회귀 확인
```

## 9. 비용 락 정리

- **Lite 유지 + PAYG 미사용 = 하드 $0.** 50k 토큰/월 초과 시 과금 대신 차단되고, 앱은 [WatsonxLlmClient] 폴백 메시지 반환.
- 토큰 아끼려면 데모 코퍼스 작게(골든셋 수준), 불필요한 재적재 자제.
- 자세한 비용 분석은 [CLOUD-DEPLOYMENT.md], 모니터링은 [MONITORING.md].
