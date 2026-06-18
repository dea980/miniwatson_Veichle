# MiniWatson Vehicle — Frontend (Next.js)

기존 정적 HTML UI를 **Next.js(App Router + TypeScript)** 로 포팅한 프론트엔드.
백엔드(Spring, :8080)의 REST API를 그대로 호출한다.

## 구조

```
frontend/
  app/         layout.tsx · page.tsx(탭) · globals.css
  components/  AskPanel · KnowledgeBasePanel · TabularPanel · GovernancePanel
  lib/api.ts   타입드 API 헬퍼
  next.config.js  /api/* → 백엔드 프록시(rewrites) → CORS 불필요
```

## 탭

- **RAG 검색** — 질문 + namespace + 모델 선택 → 답변 + 근거(sources) + 피드백
- **Knowledge Base** — 파일 업로드(인제스트) + 문서 목록/삭제
- **Tabular SQL** — CSV 로드 + 자연어 → SQL(DuckDB) (리콜 데이터)
- **Governance** — 통계 카드 + 감사 로그(Audit Trail)

## 실행 (로컬)

```bash
cd frontend
npm install
npm run dev            # http://localhost:3000
```

- 백엔드가 `http://localhost:8080`에 떠 있어야 함 (`./mvnw spring-boot:run`).
- 백엔드 주소가 다르면: `BACKEND_URL=http://host:port npm run dev`

## 배포 (Vercel + 백엔드 분리)

1. **프론트** → Vercel (이 `frontend/` 디렉터리). 환경변수 `BACKEND_URL`에 배포된 백엔드 주소.
2. **백엔드** → Cloud Run / Fly.io 등 (기존 Dockerfile). 공개 데모는 `llm.provider=watsonx`로 스왑(GPU 불필요).
3. 온디바이스 파인튜닝 모델은 로컬 데모(Ollama)로 시연.

> rewrites가 `/api/*`를 `BACKEND_URL`로 프록시하므로 별도 CORS 설정 없이 동작.
> (백엔드를 직접 노출해 브라우저가 교차출처로 호출하려면 Spring에 CORS 허용 필요.)
