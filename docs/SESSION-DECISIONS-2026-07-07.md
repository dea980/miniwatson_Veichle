# 결정 로그 — 2026-07-07 (정직 평가 · 컨텍스트 누수 · UI 폴리싱 · 실행 런북)

이번 라운드의 결정과 **"왜"** 를 면접 답변 그대로 쓸 수 있게 정리. (증상/요구) → (결정) → (근거) 순.
앞선 라운드: [SESSION-DECISIONS-2026-07-01.md](SESSION-DECISIONS-2026-07-01.md).

---

## 1. RAGAS 재측정 — 3-judge 삼각검증 + 메타필터 (정직 평가)

**요구**: fable 진단 ② — RAG 품질을 숫자로 확인(T0'가 예전에 Connection refused로 보류됨).

**결정/발견**(상세·표는 [RAG-EVAL-RAGAS.md](RAG-EVAL-RAGAS.md) §8):
- **강한 judge 둘(qwen3:8b 0.50, granite4 0.53)이 faithfulness 일치** → 신뢰 지표. KB를 2매뉴얼→124문서로 키워도 안 올라 = **grounding 갭은 실재**(KB 크기 문제 아님).
- **1.5B FT judge만 1.00** → 약한 judge는 변별 못 함(전부 도장). LLM-as-judge의 대표 함정을 데이터로 증명.
- **강한 judge끼리도 context-recall은 0.23↔0.89로 갈림** → judge 분산은 메트릭마다 다름. faithfulness=안정, context 메트릭=노이즈.
- **메타필터(car=tucson) 실험**: precision은 올랐지만(granite4 0.50→0.80) faith가 떨어짐 — **필터 결함이 아니라 "generic 골든셋 + 차종 필터 = 부당 비교"**. 규약: 필터 A/B는 골든 범위를 필터에 맞춰야 유효.

**근거/면접**: "숫자 올리기"가 아니라 **"judge를 못 믿는 법을 안다"**(강한 judge 다수 합의만 인용, 7B+ 필수, 부당 조합 배제)가 MLOps 성숙도. 개선 목표: faithfulness 0.5→0.8(retrieval: TOP_K·rerank·청킹).

## 2. 컨텍스트 아티팩트 누수 — `labels: 41; [OCR]` (라이브 재현 + 스크럽)

**증상**: 엑사원→FT(1.5B)로 바꾸자 "프리텐셔너 주의사항?" 두 번째 답변이 **문장 중간에 끊기고**(`…얼굴에 마`) **검색 청크의 OCR 메타데이터(`labels: 41; [OCR]`)를 그대로 뱉음**.

**진단**: 매뉴얼 PDF를 OCR해 넣은 컨텍스트에 `labels:`·`[OCR]` 마커가 있는데(프롬프트가 "정확한 숫자는 `[OCR]` 섹션 신뢰"라고 **의도적으로** 씀), 약한 모델이 종합을 못 하니 그걸 복붙. `docs/RESULTS.md §2.2`의 "1.5B FT 붕괴·아티팩트 누수"가 **라이브로 재현**된 것.

**결정**:
- **컨텍스트의 `[OCR]`는 유지**(강한 모델의 숫자 근거라 지우면 손해) → 문제는 **답변 쪽**이니 **생성된 답변에서 아티팩트를 스크럽**(CJK 스크럽과 같은 패턴). `labels:\s*\d+`·`[OCR]`·청크 id(`#\d{3,}`) 제거.
- 위치: `RagService`의 생성 직후 또는 `RagCacheService.postProcess`(응답 정제 미들웨어)에 한곳.
- 근본은 **강한 베이스로 서빙**(이미 기본 결정) — FT는 "약점 비교용".

**근거**: 누수는 답변에서 보이므로 생성 후 정제가 정답. 컨텍스트에서 통째로 지우면 의도된 OCR 근거까지 날아감(관심사 분리). 면접: "FT로 바꾸니 답이 붕괴하고 OCR 태그를 뱉더라 → 강한 베이스 서빙 + 답변 스크럽으로 대응."

## 3. 마크다운 렌더 — 채팅·매뉴얼검색 답변

**증상**: AI 어시스턴트/매뉴얼검색 답변이 `* 항목`을 **raw로 표시**(마크다운 미렌더). 첫 줄만 `•`(모델이 낸 리터럴), 나머지는 `*` 노출 → "누가 봐도 미완성".

**결정**: 답변을 기존 `Markdown` 컴포넌트로 렌더(`HomePanel` assistant 말풍선, `AskPanel` 답변). `Markdown`의 불릿 인식에 **`•` 추가**(`[-*•]`) — 모델이 리터럴 불릿을 내도 리스트로. 사용자 입력 말풍선은 평문 유지.

**근거**: 이미 있는 컴포넌트 재사용(의존성 0). DiagnosePanel·CaseTriage는 이미 Markdown을 써서 일관성만 맞춘 것.

## 4. 커스텀 드롭다운 `Select` + portal — 네이티브 select 대체

**증상**: 모델/차종 select의 열린 목록이 **OS 네이티브 팝업**(큰 폰트·테마 불일치)이라 "깨져" 보임(특히 macOS). 이후 커스텀으로 바꾸니 **드롭다운이 카드 뒤로 잘리고 배경이 투명**(footer 비침).

**결정**:
- `Select.tsx` — 버튼 + 목록 직접 구현으로 네이티브 팝업 제거. 앱 전체 select 교체(모델·차종·정렬·상태·스케줄).
- 투명 배경 = **`var(--card)` 변수가 이 앱엔 없음**(앱은 `--surface` 사용) → `--surface`로 교체(`.trend-tip`도 같은 버그였음).
- 카드에 갇힘 = 카드 진입 애니메이션(`card-rise`)이 남긴 transform이 **stacking context**를 만듦 → 메뉴를 **React portal로 `.shell`에 렌더 + `position:fixed`**(테마 스코프 유지하며 카드 탈출). z-index 100.

**근거**: 네이티브 `<option>`은 스타일 불가(브라우저가 그림). portal은 stacking/overflow 탈출의 정석. `.shell`(document.body 아님)에 붙여야 다크 테마 변수(`--surface` 등)를 상속. **브라우저 실측으로 검증**(parent=.shell, bg=rgb(13,20,32), z=100).

## 5. CSS 단일 파일 → 7파일 분리

**요구**: `globals.css`(418줄)가 너무 길다.

**결정**: 섹션 주석 기준으로 `app/styles/`의 7파일(tokens·shell·media·layout·forms·components·charts)로 슬라이스, `globals.css`는 `@import`만. className 불변 → 컴포넌트 수정 0.

**근거**: 원본과 **diff 0**(규칙 손실 없음)·파일별 중괄호 균형 0으로 검증. `@import` 순서=원본이라 cascade 동일. Next가 빌드 시 인라인.

## 6. UI 폴리싱 (자잘하지만 "AI 티" 제거)

- **차종명 국내명 표시 통일**(SANTA FE→싼타페) — 데이터 키·이미지·네비는 원본, 표시만 매핑.
- **차량 이미지 변형 접미사 처리** — `VELOSTER N`·`SANTAFE HYBRID` 등 변형 위키 문서엔 썸네일이 없어 폴백으로 떨어짐 → **베이스 모델 문서로**(접미사 N·HYBRID·EV·FCEV 제거, 숫자 모델명 IONIQ 5는 보존).
- **테마 버튼 짤림** — topbar `justify-content: space-between`이 crumb가 길면 우측 컨트롤을 밀어냄 → crumb `flex:1 + 말줄임`, 우측 `flex-shrink:0`. **브라우저 실측**(긴 crumb에서 토글 751px 밖→우측 40px 여백).
- **히어로 파란 눈금선 제거**(`.car-hero::after` 삭제) · **중요도 %(밴드 지수)** 표시 · **" · " 구분자→ "|"**.

**근거**: 실무 UI는 크롬(장식) 최소·상태 절제·데이터 우선. "누가 봐도 AI가 만든" 시그니처를 걷어냄.

## 7. 실행/트러블슈팅 런북

**요구**: 백엔드 기동 때마다 같은 에러(pgvector 미기동·8080 좀비·Semeru 크래시)로 헤맴.

**결정**: [RUNBOOK.md](RUNBOOK.md)에 **빠른 실행 순서 + 트러블슈팅 표**(증상→원인→해결) 추가. Temurin·pgvector(docker)·`-Xmx4g`·포트 정리·`embedding.provider` 분리·OCR 누수까지 한 표.

**근거**: 흩어진 지식(HOTSPOT-RUNTIME·PGVECTOR·OPERATIONS)을 실행 시점 한 장으로 모아 재현성·속도 확보.

---

## 배포/적용 메모
- 프론트 변경(Markdown 렌더·Select portal·CSS 분리·이미지·테마버튼·중요도%)은 **새로고침**이면 반영.
- 백엔드 변경(OCR 아티팩트 스크럽 — *직접 구현*)은 **재빌드 필요**. 실행은 [RUNBOOK.md](RUNBOOK.md) "빠른 실행".
- 검증: 프론트 `tsc --noEmit` 0, Select/테마버튼은 **브라우저 실측**, CSS 분리 diff 0, RAGAS 3-judge 실측.
