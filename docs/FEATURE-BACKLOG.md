# 기능 백로그 (단일 우선순위 — "차근차근" 소스)

흩어진 로드맵(HYUNDAI_NEEDS·RAG-ACCURACY·README·hardening·cicd-cloud·integrated-query)을 **하나로 합친
우선순위 목록**. 상태(✅완료/🔄진행/⚪예정), 노력, 두 공고(A=현대모비스 AI Agent / B=풀스택·SI) 적합도.
**기술 트랙**(아래 "차근차근")과 **제품 트랙**(실사용 A/S 기능, §제품 트랙) 두 축으로 관리.

> 원칙: 매 항목 **측정/검증으로 끝낸다**. 작은 것부터(차근차근). 출처 문서는 각 항목에 링크.

---

## 완료 로그

### 2026-07-06 — 제품 트랙 P1~P5 + 기술 2번 완료
- ✅ **P1 케이스 상태 워크플로** — `resolved_case`+status 컬럼, `/api/analytics/case-status`,
  큐는 완료만 제외(진단중/수리중은 상태칩), HomePanel localStorage 제거. 커밋 `ad0f1af`
- ✅ **P2 진단→예약→완료 루프** — 케이스 상세 "이 케이스로 예약" → 수리중, 일정 완료 → 케이스 완료. 커밋 `ad0f1af`
- ✅ **P3 유사 케이스 검색** — 토큰 코사인 근사 top-5(부위 우선), 상세에서 연쇄 이동. 커밋 `33a928b`
- ✅ **P4 리콜 대상 조회** — 홈 카드, 차종+연식 → 리콜 목록(주차권고 우선). 커밋 `0d7eb92`
- ✅ **기술 2번 PII before/after** — `/api/governance/mask-preview`, 거버넌스 탭 원문/마스킹 대조. 커밋 `0d7eb92`
- ✅ **P5 진단 리포트 인쇄/PDF** — print.css(라이트 강제·no-print) + 🖨 버튼. 커밋 `843a89b`
- ✅ **P6 주간 품질 브리핑** — weeklyStats(결정적 SQL) + LLM 서술, BRIEFING 캐시(17s→0.16s). 커밋 `363c363`
- 프로세스 상세 문서: [`AS-OPERATIONS.md §9 케이스 워크플로`](AS-OPERATIONS.md)

### 2026-07-03
- ✅ **UI 콕핏 디자인 패스** — 다크 콕핏 기본 테마 + 사이드바 다크 레일(양 테마) + KPI 44px Rajdhani
  디스플레이 타이포 + 카운트업·카드 스태거 모션 + 히어로 계기 눈금 라인. 실렌더링(Playwright) 전/후
  스크린샷 검증(`frontend/design-shots/`). 파일: `globals.css`·`layout.tsx`·`page.tsx`·`HomePanel.tsx`.
- ✅ **Agent 트레이스 UI** (구 추천 1번) — 커밋 `431a56b`·`643cdbe` (트레이스 시각화 + 응답 가드).
- ✅ **CI (GitHub Actions)** (구 추천 3번) — 커밋 `70e12fa`·`d1501a2` (백엔드 테스트 + 프론트 빌드 게이트).
- ✅ **서빙 트랙(vLLM)** — `VllmLlmClient`(OpenAI 호환) 배선 + prefix-cache/배칭 벤치(`ml/optimize/bench_serving.py`), README V18.
- 🔧 운영 메모: 로컬 백엔드는 **arm64 JDK로 기동**할 것 — x86_64 JDK(Rosetta)로 돌리면 JIT SIGBUS 크래시
  (`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-23.jdk/Contents/Home ./mvnw spring-boot:run`).

### 2026-06 (이전 세션)
- ✅ pgvector 전환 + 선별 적재(wave) · 최신성 가중 우선순위 · jsr310 적재 버그
- ✅ OEM 콕핏 UI · 인터랙티브 도넛 · 캐러셀 · 자동갱신 · 차종/연식 메타필터 UI
- ✅ **RAG 답변 캐시**(compute-once + KB버전 무효화) — k6 p95 57s→4ms (`GUIDE-answer-cache`)
- ✅ k6 부하 기준선(웹 60VU 56ms / RAG 5VU 57s) (`ROADMAP-backend-hardening §0.5`)
- ✅ Dagster asset-per-book 스캐폴드 (`pipelines/dagster`)

---

## 제품 트랙 — 실사용 A/S 기능 (2026-07-03 신설)

기술 데모를 넘어 **"서비스 어드바이저가 하루 업무(접수→진단→예약→완료)를 이 안에서 끝낼 수 있는가"**를
기준으로 뽑은 기능들. 기존 조각의 연결이 대부분이라 노력 대비 데모 스토리 완성 효과가 큼.

> **2026-07-06: P1~P6 전부 완료** (완료 로그 참고) — 제품 트랙 종료. 다음은 기술 트랙 4·5.

### P1. 케이스 상태 워크플로 — 백엔드 영속  ✅  노력: 낮음
- **왜**: 지금 "해결" 처리가 `localStorage`(`mw-resolved-cases`)에만 저장 — 브라우저 바꾸면 소실, 공유 불가.
  접수→진단중→수리중→완료 상태를 JPA 테이블로 영속하면 장난감→업무 도구가 된다.
- **어떻게**: 정비 스케줄과 같은 패턴(기존 JPA 데이터소스에 테이블 추가). 파일: 신규 엔티티+컨트롤러,
  `HomePanel`·`CaseTriagePanel`의 resolved Set을 API로 교체.

### P2. 진단→예약→완료 루프 연결  ✅  노력: 낮음
- **왜**: 케이스 진단·스케줄 기능이 서로 안 이어져 있음. "이 케이스로 정비 예약" 버튼 → 케이스 ID 달린
  일정 생성 → 완료 시 케이스도 완료. **P1과 합쳐 반나절, 데모 스토리(접수부터 완료까지) 완성.**
- **어떻게**: `CaseTriagePanel`에 예약 버튼, `SchedulePanel` 일정에 caseId 필드.

### P3. 유사 케이스 검색  ✅  노력: 중
- **왜**: "과거에 같은 증상 접수 있었나?"가 현장의 실제 첫 질문. 기존 임베딩 인프라(`EmbeddingService`)
  재활용이라 RAG 스택 재사용 어필도 큼.
- **어떻게**: 불만 원문 임베딩 → 케이스 상세에 top-5 유사 케이스 + 당시 리콜 여부 표시.

### P4. VIN/차종·연식 → 리콜 대상 조회  ✅  노력: 낮음
- **왜**: 고객 응대는 "제 차 리콜 대상인가요?"로 시작. 입력 폼 + 기존 DuckDB 쿼리 조합.

### P5. 진단 리포트 인쇄/PDF  ✅  노력: 낮음
- **왜**: 고객에게 건네줄 산출물. `window.print()` + print CSS면 충분.

### P6. 주간 품질 브리핑 자동 생성  ✅ (2026-07-06 완료, 커밋 363c363)  노력: 중
- **왜**: "이번 주 신규 불만 N건·급증 차종/부위·요주의 케이스" LLM 요약. 기존 summary API + 캐시 패턴 재활용.

### 안 만들기로 한 것 (의도적)
- 사용자 계정/권한(PoC 과함) · 실시간 웹소켓 알림(20초 폴링으로 충분) · 모바일 앱(반응형 웹으로 충분).

---

## 차근차근 추천 순서 (기술 트랙, 다음 → 그다음)

### 1. Agent 트레이스 UI  ✅ (2026-07 완료, 커밋 431a56b)  [A⭐ B✅]
- **왜**: "AI Agent 구조"는 A공고 정통 키워드. 백엔드 `AgentResult.trace` → 세로 스텝퍼 시각화 완료.
- 출처: `HYUNDAI_NEEDS §4 P3`. 파일: `AgentPanel.tsx`.

### 2. PII 마스킹 before/after 화면  ✅ (2026-07-06 완료, 커밋 0d7eb92)  [A⭐ B✅(백오피스)]  노력: 중
- **왜**: 네 문서가 "면접 제일 먼저 꺼낼 카드"라 한 거버넌스 차별점(H-Chat 정합). 마스킹 전/후 토글.
- 출처: `HYUNDAI_NEEDS §3·§5`. 파일: `GovernancePanel.tsx` + 백엔드 마스킹 노출.

### 3. CI (GitHub Actions)  ✅ (2026-07 완료, 커밋 70e12fa·d1501a2)  [A✅ B⭐]
- push마다 백엔드 테스트 + 프론트 빌드 게이트. 파일: `.github/workflows/ci.yml`.

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
- ~~1~3은 노력 낮음 + 두 공고 동시 타격~~ → **1·2·3 모두 완료. 기술 트랙 다음 타자는 4(통합 질의) 또는 5(RAG 품질, 측정 먼저)**.
- **4~6은 "AI/평가/거버넌스"** = A공고 깊이.
- **7~9는 큰 작업** = 시간 날 때. 배포(7)는 B공고 필수라 코테/마감 일정과 저울질.
- **제품 트랙(P1~P6)은 기술 트랙과 병행** — P1+P2가 반나절로 "접수→완료" 데모 스토리를 완성하므로
  데모/포트폴리오 임팩트 기준으론 P1→P2→P3가 최우선. 기술 깊이 어필 기준으론 2(PII)→5(RAG 품질).
- 면접 마감(7/10 지원, 7/23~ 코테) 고려: **작은 것 위주로 빠르게, 코테/영어/Softeer 병행**이 현실적.
