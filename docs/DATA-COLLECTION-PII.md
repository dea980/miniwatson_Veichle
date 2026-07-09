# 데이터 수집 & PII 정책 — NHTSA 불만 (Geographic 확장)

> 목적: 분석 대시보드에 **지역(state) 차원**을 추가하기 위해 NHTSA 불만 원본(flat file)을 재수집하되, **PII를 최소화**한다. 거버넌스 서사([GovernanceLlmClient]·PiiRedactionService)와 일관.

## 1. 소스

- **NHTSA ODI 불만 flat file**: `https://static.nhtsa.gov/odi/ffdd/cmpl/FLAT_CMPL.zip`
  (5년 청크: `COMPLAINTS_RECEIVED_YYYY-YYYY.zip`)
- 레이아웃: `https://static.nhtsa.gov/odi/ffdd/cmpl/CMPL.txt` (최종 갱신 2026-04-30, 51개 필드)
- 형식: **탭 구분, 헤더 없음, 필드=순번 매핑**. 인덱스 = 문서필드번호 − 1.

## 2. API vs flat file — 왜 flat file인가

기존 CSV는 NHTSA **API**로 수집 → 위치 필드 없음(11컬럼). flat file엔 **`STATE`(#14)·`STATE_OF_INCIDENT`(#50, 2026-04 신규)** 가 있어 지역 집계가 가능. 그래서 flat file로 전환.

## 3. 수집 컬럼 (curated wide, 비PII)

| 수집(분석용) | 출처 필드 |
|---|---|
| 기존 11: odiNumber, dateComplaintFiled, make, model, modelYear, components, crash, fire, numberOfInjuries, numberOfDeaths, summary | #2,#17,#4,#5,#6,#12,#7,#9,#10,#11,#20 |
| **state, stateOfIncident** (지역 렌즈) | #14, #50 |
| **fuelType** (EV/전동화 렌즈: HE=하이브리드전기) | #30 |
| driveTrain, transType, failDate, miles | #28,#31,#8,#18 |

## 4. PII 처리 — 2층 방어 (privacy by design)

**층 1 — 수집 단계 최소화(data minimization): 아래 필드는 애초에 미수집.**

| 미수집 필드 | 사유 |
|---|---|
| CITY (#13) | 도시 단위 = 식별성↑. 주(state)로 일반화 |
| VIN (#15) | 차량 식별자 |
| DEALER_NAME/TEL/CITY/ZIP (#41–45) | 딜러 연락처 |
| VEHICLE_OPERATOR (#51) | 운전자 실명 |

원칙: **없으면 샐 수도 없다.** 집계 분석엔 위 필드가 불필요하므로 수집 자체를 안 한다.

**층 2 — 적재 단계 리댁션: 자유텍스트(summary/CDESCR)의 잔여 PII 마스킹.**

소비자가 직접 타이핑한 설명엔 이름·번호판·전화·이메일이 섞일 수 있음.
- 수집 스크립트: 이메일·전화·VIN 패턴 **1차 스크럽**(커밋 아티팩트 보호).
- 적재(IngestionService): `PiiRedactionService`로 **심층 리댁션** 후 저장·임베딩.

## 5. 데이터 한계 (정직 표기)

- NHTSA = **미국 주(state)** 단위 → 국내(한국) 서사로 쓸 땐 **"방법론 이식"**(필드만 교체, 파이프라인 동일)으로 표기. 지도는 후속.
- `stateOfIncident`(사고 발생지)가 `state`(소비자 거주지)보다 핫스팟 분석에 정확 — 둘 다 보관해 비교.

## 6. 면접 한 줄

> "지역 분석을 위해 NHTSA flat file로 재수집하면서, 원본의 VIN·운전자 이름·딜러 연락처는 **수집 단계에서 최소화(미수집)**하고 자유텍스트는 **적재 시 PII 리댁션**을 거쳐 주(state) 단위 집계만 노출했다 — privacy by design + 2층 방어."

## 7. 실행

`scripts/fetch_complaints_with_state.py` (신규 파일로 먼저 뽑아 검증 후 교체). 명령은 [RUNBOOK.md] 또는 세션 기록 참조.
