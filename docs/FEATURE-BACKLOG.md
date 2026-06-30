# 기능 백로그 (단일 우선순위 — "차근차근" 소스)

흩어진 로드맵(HYUNDAI_NEEDS·RAG-ACCURACY·README·hardening·cicd-cloud·integrated-query)을 **하나로 합친
우선순위 목록**. 상태(✅완료/🔄진행/⚪예정), 노력, 두 공고(A=현대모비스 AI Agent / B=풀스택·SI) 적합도.

> 원칙: 매 항목 **측정/검증으로 끝낸다**. 작은 것부터(차근차근). 출처 문서는 각 항목에 링크.

---

## 이번 세션에 끝낸 것 (✅)
- ✅ pgvector 전환 + 선별 적재(wave) · 최신성 가중 우선순위 · jsr310 적재 버그
- ✅ OEM 콕핏 UI · 인터랙티브 도넛 · 캐러셀 · 자동갱신 · 차종/연식 메타필터 UI
- ✅ **RAG 답변 캐시**(compute-once + KB버전 무효화) — k6 p95 57s→4ms (`GUIDE-answer-cache`)
- ✅ k6 부하 기준선(웹 60VU 56ms / RAG 5VU 57s) (`ROADMAP-backend-hardening §0.5`)
- ✅ Dagster asset-per-book 스캐폴드 (`pipelines/dagster`)

---

## 차근차근 추천 순서 (다음 → 그다음)

### 1. Agent 트레이스 UI  ⚪  [A⭐ B✅]  노력: 낮음
- **왜**: "AI Agent 구조"는 A공고 정통 키워드. **백엔드가 이미 `AgentResult.trace`를 반환** → UI로 단계
  (질문→툴선택 RAG/SQL→실행→종합) 시각화만. 제일 빠른데 임팩트 큼.
- 출처: `HYUNDAI_NEEDS §4 P3`. 파일: `AgentPanel.tsx`(trace 렌더), 백엔드 그대로.

### 2. PII 마스킹 before/after 화면  ⚪  [A⭐ B✅(백오피스)]  노력: 중
- **왜**: 네 문서가 "면접 제일 먼저 꺼낼 카드"라 한 거버넌스 차별점(H-Chat 정합). 마스킹 전/후 토글.
- 출처: `HYUNDAI_NEEDS §3·§5`. 파일: `GovernancePanel.tsx` + 백엔드 마스킹 노출.

### 3. CI (GitHub Actions)  ⚪  [A✅ B⭐]  노력: 낮음(반나절)
- **왜**: 두 공고 우대(CI/CD). push마다 백/프 빌드·테스트. 가장 빠른 "이력서 한 줄".
- 출처: `ROADMAP-cicd-cloud Phase 1`. 파일: `.github/workflows/ci.yml`.

### 4. 통합 질의 (차종·연식 핫스팟 + 추가 점검 추천)  ⚪  [A⭐ B✅]  노력: 중
- **왜**: agentic. "정형 신호로 무엇을 볼지 좁히고 비정형으로 어떻게 답한다."
- 출처: `ROADMAP-integrated-query`(단계별 가이드 있음). 신규: `modelYearHotspots` + 종합 메서드.

### 5. RAG 품질 — 리랭커 + 섹션 메타 + 환각 완화  ⚪  [A⭐]  노력: 중
- **왜**: KB가 커지며 차종 섞임. 메타필터·BM25는 ✅ → 다음은 크로스인코더 리랭커, 섹션(헤딩) 메타,
  "컨텍스트에 없으면 모른다" 프롬프트로 faithfulness↑. **단, 골든셋 확장 + RAGAS delta로 측정 먼저.**
- 출처: `RAG-ACCURACY-ROADMAP`(3단계 표 + 결정점). 측정: `eval/run_ragas.py`.

### 6. Experiment 탭 (모델 A/B + 검색 노브)  ⚪  [A⭐(평가) B✅]  노력: 중
- **왜**: "AI 평가 자동화" 정합. base vs FT, rerank/hybrid on/off 비교 UI.
- 출처: `HYUNDAI_NEEDS §5`.

### 7. 클라우드 배포  ⚪  [A✅ B⭐]  노력: 큼(하루+)
- **왜**: B공고 핵심 우대. Ollama→hosted LLM 스왑(추상화 있음) → stateless → Cloud Run + 관리형 PG.
- 출처: `ROADMAP-cicd-cloud Phase 2~4`.

### 8. 백엔드 하드닝 Step 2~7  ⚪  [A✅ B✅]  노력: 중~큼
- 풀/스레드 튜닝(측정상 후순위)·임베딩 비동기·EXPLAIN·토큰절약·MCP 서버·tcpdump.
- 출처: `ROADMAP-backend-hardening`.

### 9. 자체 도메인 LLM — LoRA 품질 + eval 정량화  ⚪  [A⭐]  노력: 큼(ML)
- **왜**: Nemotron류 "자체 도메인 LLM" 정합. base vs FT 정량 비교.
- 출처: `HYUNDAI_NEEDS §4 P2`, `README §6`, `RESULTS.md`.

---

## 기타 갭 (작게, 끼워넣기)
- ⚪ 거버넌스: audit 저장 실패 격리(try/catch), `/logs` 필터(endpoint/model/id). 출처 `GOVERNANCE.md §8`.
- ⚪ NHTSA 전체 summary 재수집(`refetch_nhtsa_complaints.py` → `/refresh`) — 접수내용 짤림 해결.
- ⚪ float[] 임베딩 근본수정(OOM) — 큰 리팩터, 적재 누적 137 해결. 출처 `RAG-INGEST-SCALING §6`.

## 우선순위 근거 (왜 이 순서)
- **1~3은 노력 낮음 + 두 공고 동시 타격** → 빠른 이력서 강화(차근차근의 첫 묶음).
- **4~6은 "AI/평가/거버넌스"** = A공고 깊이.
- **7~9는 큰 작업** = 시간 날 때. 배포(7)는 B공고 필수라 코테/마감 일정과 저울질.
- 면접 마감(7/10 지원, 7/23~ 코테) 고려: **기능은 1~3 위주로 빠르게, 코테/영어/Softeer 병행**이 현실적.
