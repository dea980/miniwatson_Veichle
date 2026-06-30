# 로드맵 — 통합 질의(차종·연식 인지 + 불만/리콜 핫스팟 + 추가 점검 추천)

**목표(UX)**: 사용자가 차종·연식(예: 2020 PALISADE)을 주면 →
① 그 차·연식의 **불만/리콜 핫스팟**(가장 많이 보고된 부위·화재·부상)을 집계하고,
② 그걸 근거로 **매뉴얼 RAG**(해당 부위 점검·조치)를 엮어,
③ **"이 차·연식은 X가 빈발 → 매뉴얼 Y·Z를 같이 보라"는 추가 점검 추천**을 종합해 보여준다.

> "단순 검색"이 아니라 **데이터(불만·리콜) + 지식(매뉴얼)을 합친 의사결정 보조**. 면접 한 줄:
> "정형 신호로 *무엇을 볼지*를 좁히고, 비정형 매뉴얼로 *어떻게 볼지*를 답한다."

이 문서는 **네가 직접 구현**하는 가이드다. 단계마다 (파일 · 무엇 · 왜 · 검증)을 적었다.

---

## Phase 0 — 이미 있는 조각 (재사용, 새로 안 짬)
| 조각 | 위치 | 쓸 곳 |
|---|---|---|
| 메타필터 RAG (`car/year/lang/powertrain`) | `RagService.ask(...full)` · `AskRequest` DTO | ② 매뉴얼을 그 차·연식으로 좁혀 검색 |
| 차종 케이스 집계 | `AnalyticsService.vehiclesByModel(model)` | ① 핫스팟(단 **연식 필터 없음** → Phase 1에서 추가) |
| 점검표 | `AnalyticsService.checklist(model, component)` | ③ 부위별 점검 항목 |
| 에이전트 자동 라우팅(RAG/SQL/둘다) | `AgentService.run(q, ns, model)` | ③ 종합 흐름의 모델 |
| 요약 1회+캐시 패턴 | `ReportService.caseSummary` (GeneratedReport type별) | ④ 종합 결과 캐시 |
| 프론트 차종/연식 입력 | `AskPanel`의 "범위 좁히기" (방금 추가) | ③ AgentPanel에 동일 패턴 복붙 |

→ **핵심 신규 작업은 "연식 필터 집계" 하나 + "종합 메서드" 하나.** 나머지는 조립.

---

## Phase 1 — 백엔드: 연식 인지 핫스팟 집계
**파일**: `AnalyticsService.java`
**무엇**: `modelYearHotspots(String model, Integer year)` 추가.
- complaints에서 `upper(model)=? [AND modelyear=?]` 필터 → `components` 별 COUNT 내림차순 top N,
  + 화재/부상/사망 합. (recalls도 같은 방식으로 component top N — 별도 키로.)
- 반환 예: `{ model, year, complaintTop:[[부위,건수]...], recallTop:[[부위,건수]...], fires, injuries, deaths }`
**왜**: ①의 데이터 원천. 기존 집계는 model 단위뿐 → 연식 축을 더해 "연식별 차이"를 가능케.
**참고 코드**: `cases()`·`vehiclesByModel()`의 SQL 패턴(fireT/crashT/inj/dea, `upper(model)=`)을 그대로 응용.
연식 컬럼은 `modelyear`(complaints) — `caseById` SELECT에 이미 등장(`c.get(4)`).
**검증**:
```sql
-- DuckDB 직접 확인 (스크립트로): 2020 PALISADE 부위별 top
SELECT components, COUNT(*) n FROM complaints
WHERE upper(model)='PALISADE' AND modelyear='2020' GROUP BY components ORDER BY n DESC LIMIT 5;
```
컨트롤러: `AnalyticsController`에 `GET /api/analytics/hotspots?model=&year=` 추가(한 줄 위임).

## Phase 2 — 백엔드: 통합 추천 종합
**파일**: `ReportService.java` (또는 `AgentService`) — `integratedAdvice(model, year, llmModel)`.
**무엇** (순서):
1. `analytics.modelYearHotspots(model, year)` → 상위 부위 N개.
2. 상위 부위마다 `ragService.ask(질의, "vehicle", llmModel, null, model, year, null, null)` —
   **메타필터로 그 차·연식 매뉴얼에서** 점검·조치 근거 retrieval.
3. `ollama.ask(프롬프트, llmModel)`로 종합: "이 차·연식은 [부위들]이 빈발(건수·화재 N). 매뉴얼 근거상
   추가로 [점검항목]을 우선 점검하라" — 3~5문장 + 부위별 1줄.
**왜**: ①(무엇을)·②(어떻게)를 한 답으로. RAG 단독이 못 주는 "우선순위 있는 점검 가이드".
**캐시(④ 미리)**: 결과를 `GeneratedReport` type="ADVICE", key=`model:year`로 저장 →
`caseSummary`와 동일한 **compute-once+cache** 패턴(처음 1회만 LLM). force=true로 갱신.
**검증**: `GET /api/agent/integrated?model=PALISADE&year=2020` → hotspots + advice JSON. 2회차는 `cached:true`.

## Phase 3 — 프론트: AgentPanel(통합 질의) UI
**파일**: `AgentPanel.tsx` + `lib/api.ts`
**무엇**:
- `api.integrated(model, year)` 추가(Phase 2 엔드포인트 호출).
- AgentPanel에 **차종/연식 입력**("범위 좁히기" — `AskPanel`에서 방금 만든 행 그대로 복붙).
- 렌더: **핫스팟 도넛**(`Donut` 컴포넌트 재사용, 부위별 건수) + **추가 점검 추천 카드**(`.answer`에 Markdown).
**왜**: 입력은 차·연식, 출력은 "무엇을 볼지(도넛) + 어떻게(추천)". 기존 자동 라우팅 질의는 유지하고 탭/토글로 병행.
**검증**: 2020 PALISADE 입력 → 도넛에 SEAT BELTS 등 상위 부위, 추천 카드에 매뉴얼 근거 점검 항목.

## Phase 4 — 캐시·문서·연식 데이터 보강
- Phase 2 캐시로 재방문 즉시. 데이터가 그 차·연식 매뉴얼/불만을 포함해야 품질이 나옴 → wave 적재가 받쳐줘야.
- 결정 로그에 "통합 질의 = 정형 신호로 범위 좁히고 비정형으로 답" 한 단락 추가.

---

## 운전 팁 (막히면)
- **연식 컬럼 타입**: `modelyear`가 문자열일 수 있음 → `CAST(... AS VARCHAR)` 비교 또는 `TRY_CAST`.
- **메타필터가 비면**: 그 차·연식 매뉴얼이 KB에 없을 수 있음(선별 적재). 결과 적으면 적재부터.
- **LLM 종합이 느림**: Phase 2 캐시가 그래서 필요. 처음 1회만 감수.
- **막히는 지점 = 나한테**: 파일·증상 주면 그 단계만 같이 디버그(투입 코드는 네가).

## 의존 순서
Phase 1(집계) → 2(종합+캐시) → 3(UI). 1만 해도 "연식별 핫스팟 표/도넛"은 바로 가치 있음(점진 배포).
