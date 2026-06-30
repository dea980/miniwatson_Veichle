# 로드맵 — CI/CD · 클라우드 배포

**왜**: 두 채용 공고 공통 갭 = **클라우드 배포 + CI/CD + 테스트**. 메우면 양쪽 지원서가 동시에 강해진다.
네가 운전하는 가이드 — 각 단계 (무엇 · 왜 · 어떻게=이 프로젝트 파일 · 검증 · 면접 한 줄).

> **핵심 설계 결정**: 클라우드에서 **Ollama(로컬 LLM)는 무겁다**(8b 모델 RAM·GPU). 다행히 이 프로젝트엔
> 이미 **LLM provider 추상화**(`AzureOpenAiLlmClient`·`VertexLlmClient`·`WatsonxEmbeddingClient`)가 있다.
> ⇒ **클라우드에선 Ollama → hosted LLM으로 스왑** → 백엔드가 **stateless** → Cloud Run 같은 데 그대로 배포.
> "로컬은 Ollama, 클라우드는 hosted"를 provider 설정으로 가르는 게 이 배포의 뼈대다.

---

## Phase 1 — CI (GitHub Actions, ~반나절)  ※ 먼저
**무엇**: push/PR마다 백엔드·프론트 **빌드 + 테스트** 자동.
**어떻게**: `.github/workflows/ci.yml`
```yaml
name: ci
on: { push: { branches: [main] }, pull_request: {} }
jobs:
  backend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }   # ★ Temurin(HotSpot) — OpenJ9 크래시 회피
      - run: ./mvnw -B verify                                   # 컴파일 + 테스트(현재 16개 테스트)
  frontend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '20' }
      - run: cd frontend && npm ci && npm run build             # tsc/next 빌드 = 타입체크 포함
```
**검증**: PR에 초록 체크. README에 badge.
**면접 한 줄**: "push마다 백/프 빌드·테스트를 CI로 강제해 회귀를 막았다. JDK는 Temurin 고정(OpenJ9 크래시 이력)."

## Phase 2 — 컨테이너화 (배포 가능한 단위, ~반나절)
**무엇**: 프론트 Dockerfile 추가 + 전체 스택 compose.
**어떻게**:
- 백엔드 `Dockerfile` 이미 있음(temurin-jre, EXPOSE 8080).
- **프론트 `frontend/Dockerfile`** 신규(멀티스테이지: `npm build` → `next start` 또는 정적 export + nginx).
- **`docker-compose.deploy.yml`**: pgvector + backend + frontend 한 번에. (로컬 데모/단일 VM 배포용.)
- 환경변수로 provider 가름: `LLM_PROVIDER=ollama|azure|vertex|watsonx`, `VECTOR_STORE=pgvector`.
**검증**: `docker compose -f docker-compose.deploy.yml up` → 3000(프론트)·8080(백)·55433(pg) 전부 healthy.
**면접 한 줄**: "전 스택을 컨테이너화해 한 명령으로 띄우는 배포 단위를 만들었다."

## Phase 3 — 클라우드 배포 (~하루, 처음이면 +)
**핵심: Ollama → hosted LLM 스왑으로 stateless화.**
**옵션 (포트폴리오 현실)**:
- **백엔드** → Cloud Run / Fly.io / Render (stateless 컨테이너, scale-to-zero = 저렴). 이미지를 Phase1 CI에서 push.
- **pgvector** → 관리형 Postgres + pgvector: **Supabase / Neon / Cloud SQL**. `VECTOR_STORE=pgvector` 그대로,
  접속정보만 환경변수.
- **LLM** → Ollama 대신 **hosted**(Azure OpenAI / Vertex / Watsonx — 클라이언트 이미 있음). 키는 시크릿.
- **프론트** → Vercel(Next 네이티브) 또는 같은 컨테이너 호스트. `BACKEND_URL`만 클라우드 백엔드로.
**왜 이 구성**: LLM을 외부 API로 빼면 백엔드가 무상태 → 오토스케일·재시작 자유. (로컬은 그대로 Ollama로 개발.)
**검증**: 공개 URL에서 /ask·대시보드 동작. (데모용으로 작은 모델/저렴한 티어.)
**면접 한 줄**: "provider 추상화 덕에 로컬은 Ollama, 클라우드는 hosted LLM으로 무상태 배포했다 — 같은 코드, 환경변수로 분기."

## Phase 4 — CD (자동 배포, Phase1 확장)
**무엇**: main 머지 → 이미지 빌드 → 레지스트리 push → 클라우드 자동 배포.
**어떻게**: `ci.yml`에 deploy job 추가(`on: push: main`), `docker/build-push-action` → GHCR,
배포 호스트의 deploy hook 또는 `gcloud run deploy`. 시크릿은 GitHub Secrets.
**검증**: main에 머지하면 수 분 후 공개 URL 갱신.
**면접 한 줄**: "머지→빌드→배포를 CI/CD로 자동화했다."

---

## 테스트 보강 (우대: 테스트코드) — 병행
- 현재 16개 테스트 존재(`./mvnw verify`가 CI에서 돌림). 핵심 로직 단위테스트 추가:
  우선순위 계산(`AnalyticsService` priority/recency), 답변 캐시 키·무효화(`RagCacheService`),
  메타필터 파싱. → 커버리지 숫자도 면접 근거.

## 현실적 효과/순서
- **Phase 1(CI)** = ROI 최고·즉시. 먼저.
- Phase 2(컨테이너) → Phase 3(클라우드) → Phase 4(CD). 처음이면 Phase 3가 하루+ 걸릴 수 있음(시크릿·관리형 PG·LLM 키 세팅).
- 막히면 그 Phase의 파일·에러만 던지면 같이 디버그(코드는 네 손).

## 갭 → 충족 매핑 (두 공고 우대역량)
| 공고 우대 | 이 로드맵 |
|---|---|
| 클라우드(AWS/GCP/Azure) | Phase 3 (Cloud Run/관리형 PG/hosted LLM) |
| CI/CD 파이프라인 | Phase 1·4 |
| 테스트코드 | Phase 1 + 테스트 보강 |
| 비즈니스→기술스펙 | 이 로드맵 문서 자체가 증거 |
