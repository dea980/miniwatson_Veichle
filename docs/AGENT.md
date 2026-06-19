# Agentic Search (Agent) 설계

> 질문 → 도구 선택 → 실행 → 한국어 종합 + 트레이스. 자동차 밸류체인 NLP의 "업무 자동화 / Agentic Search".
> 코드: `AgentService`, `AgentController`(`/api/agent/ask`). UI: Next `AgentPanel`, 정적 `Agent` 패널.
> 관련: [VEHICLE_ARCHITECTURE.md](VEHICLE_ARCHITECTURE.md) · [RESULTS.md](RESULTS.md)

---

## 1. 파이프라인

```
질문
 → [라우팅] 어떤 도구? RAG / SQL / BOTH        (규칙 우선 + LLM 폴백)
 → [실행]   RAG(매뉴얼 검색) · text-to-SQL(리콜 등 정형)   (병렬 가능)
 → [종합]   결과를 한국어로 통합                 (LLM)
 → 응답 {answer, tool, trace[], sources, sql, rows}
```

trace에 각 단계(도구, 결정, 결과)를 담아 **왜 그 도구를 골랐는지 투명하게** 보여준다(Agentic Search의 핵심 UX).

## 1.1 AI 오케스트레이션 관점

`AgentService`는 단순한 "에이전트"가 아니라 **AI 오케스트레이션 계층**이다 — 여러 모델·도구·데이터소스를 하나의 답으로 지휘한다(지휘자 비유). 오케스트레이션의 표준 요소가 모두 구현돼 있다:

| 요소 | 구현 |
|---|---|
| 라우팅 | 질문 → RAG / 리콜SQL / 복합 자동 선택 (규칙 우선, LLM 폴백) |
| 작업별 모델 라우팅 | 도메인 답변=FT, SQL=강한 모델, 종합=큰 모델 (지연/품질 균형) |
| 체이닝 | 진단서: 결정적 SQL 집계 → 매뉴얼 RAG → LLM 종합 |
| 멀티모달 결합 | 이미지 진단: Vision + OCR + 매뉴얼 RAG |
| 폴백/재시도 | text-to-SQL 자기수정, 종합 실패 시 부분 리포트(graceful) |
| 거버넌스 | 모든 LLM 호출을 단일 choke point에서 감사·PII 마스킹 |

이 오케스트레이션을 **LangChain/LangGraph 없이 Java로 직접 구현**한 이유(결정성·투명성·데이터 주권·스택 일치)와 언제 프레임워크가 맞는지는 [DECISIONS.md](DECISIONS.md) 10절 참고. 핵심: 흐름이 단순(라우팅→도구→종합)하고 거버넌스 중심이라 **결정적·감사 가능한 자체 오케스트레이션**이 프레임워크 편의보다 낫다.

## 2. 라우팅 — 결정적 우선 + LLM 폴백

작은 로컬 모델은 BOTH를 남발해 RAG 노이즈를 끌어온다 → **규칙(rule) 우선**으로 모델 크기에 흔들리지 않게:

- 집계 신호(리콜, 건수, 통계, 순위, 차종별…) 있고 서술 신호 없음 → **SQL**
- 서술 신호(주의, 방법, 원인, 작동, 증상…) 있고 집계 없음 → **RAG**
- 둘 다 → **BOTH**
- 신호 없음(모호) → **LLM 분류 폴백**

> 효과: "차종별 리콜 건수"는 규칙상 SQL 단독 → RAG 노이즈 차단. "팰리세이드 리콜 몇 건+주의"는 BOTH.

## 3. text-to-SQL 자기수정 (Self-Correction)

도구 실행 실패를 그냥 에러로 두지 않고 **반성→재시도**하는 agentic 패턴:

```
SQL 생성 → 실행
  └ 실패(예: Binder Error: GROUP BY) → 에러+이전SQL을 모델에 돌려줌 → 수정 SQL 1회 재시도
```

- `TextToSqlService.ask`가 1차 실패 시 에러 메시지를 프롬프트에 넣어 재생성하고 재실행한다. 응답에 `retried:true`.
- 문법 오류(집계/GROUP BY 등)에 특히 효과적.

## 4. 모델 라우팅 (작업별 특화)

같은 질문이라도 **작업마다 적합한 모델**을 쓴다:

| 작업 | 모델 | 이유 |
|---|---|---|
| 도메인 답변(RAG 생성) | 선택 모델 (예: `vehicle-qwen2.5-1.5b` FT) | 정비 말투와 도메인 |
| **SQL 생성** | 기본 모델(`granite4`) 고정 | 코드 생성은 강한 모델이 안정적; 1.5B는 SQL 취약 |
| 종합/라우팅 폴백 | 선택 모델 | 호출자 제어 |

→ governance 감사로그에 SQL은 granite4, RAG는 선택 모델로 찍히는 게 정상(작업별 라우팅의 흔적).
선택 모델이 SQL까지 제어하게 하려면 `TextToSqlService`에 모델을 흘려보내면 되나, 소형 FT의 SQL 품질 저하를 감수해야 함.

## 5. 정형 데이터셋 레지스트리

SQL 도구 대상은 하드코딩이 아니라 **설정 레지스트리**(`vehicle.tables`)다. 새 표는 yaml 한 줄:

```yaml
vehicle:
  tables:
    recalls: data/vehicle/recalls/hyundai_recalls_nhtsa.csv
    # maintenance: data/vehicle/maintenance/schedule.csv
```

여러 개면 Agent가 질문을 보고 **테이블까지 LLM이 선택**(단일이면 그것, 폴백은 첫 테이블). `ensureLoaded`가 CREATE OR REPLACE로 idempotent 등록(재시작 후 소실 대비).

## 6. 알려진 한계 & 개선 경로 (정직)

| 한계 | 원인 | 개선 |
|---|---|---|
| 복합 질문 종합 환각 | 1.5B 소형 모델 | 종합을 더 큰 모델로 라우팅 / FT 데이터 확대 |
| 잘못된 SQL 필터(예: 팰리세이드→ELANTRA) | text-to-SQL grounding 미스 | 프롬프트에 차종 매핑 힌트 / 더 큰 모델 |
| 순수 집계는 견고, 복합 BOTH는 거침 | 라우팅, 소형 모델 | 자기수정(완료) + 모델 라우팅 |

> 핵심: **단순 질의는 안정적**, **복합 질의는 자기수정으로 견고성↑ + 종합은 큰 모델로**. 한계와 개선경로를 명시하는 게 설계 성숙도.

## 7. 확장 워크플로우 (멀티툴 캡스톤)

도구 위에 올린 상위 워크플로우 — 같은 도구들을 조합해 실제 A/S 시나리오를 만든다.

| 워크플로우 | 엔드포인트 | 도구 조합 |
|---|---|---|
| **차종 종합 진단서** | `/api/agent/report` | 리콜(SQL) + 불만(SQL) + 매뉴얼(RAG) → 섹션 리포트. 집계는 **결정적 SQL**(LLM 생성 X) |
| **이미지 진단** | `/api/agent/diagnose-image` | 사진 → Vision 캡션 + OCR → 매뉴얼 RAG → 한국어 진단 |
| **필요 부품 명세(+참고 견적)** | `/api/agent/estimate` | 증상/진단 → 부품 선택(LLM, 목록 중) → **금액 합산은 Java(결정적)**. 단가는 샘플 |

체인 데모: **사진 → 진단 → 필요 부품** (현대차 A/S: 고객 사진 문의 → 진단 → 부품 산정).

정형 데이터셋도 확장됨: `recalls`, `complaints`(NHTSA 불만), `parts`(샘플 단가) — `vehicle.tables`에 등록, Agent가 질문 보고 테이블 선택.

> 설계 원칙: **집계와 금액은 결정적 코드/SQL, 선택과 서술은 LLM.** 돈 계산과 통계를 LLM에 맡기지 않아 신뢰성 확보.

## 8. 면접 한 줄

> "Agent는 규칙 우선 라우팅으로 모델 크기에 강건하게 도구를 고르고, text-to-SQL은 실행 에러를 모델에 되먹여 자기수정한다. 도구 대상은 설정 레지스트리로 확장 가능하며, 작업별로 모델을 특화(SQL=강한 모델, 도메인답변=FT)했다. 전 과정을 trace로 투명하게 노출한다."
