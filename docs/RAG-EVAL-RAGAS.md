# RAGAS-lite 평가 하네스 (측정이 기능보다 먼저)

> "측정 없이는 개선 없다." 1·2·3단계의 효과를 같은 잣대로 비교하려면 표준 메트릭이 필요.
> 외부 `ragas` 패키지 도입은 LangChain·OpenAI 의존성 부담이 커서, **로컬 Ollama judge 기반 등가 구현**으로 시작한다.

## 1. 메트릭 — RAGAS 원논문(Es et al., 2023) 4 메트릭

| 메트릭 | 의미 | 신호 |
|---|---|---|
| **faithfulness** | 답변의 진술이 retrieved context로 뒷받침되는 비율 | 환각 정도 ↓ |
| **answer_relevance** | 답변이 질문에 얼마나 직접적으로 답하는지 | 회피/누락 ↓ |
| **context_precision** | 검색된 청크 중 실제로 관련 있는 비율 | 노이즈 ↓ |
| **context_recall** | expected의 핵심 주장이 retrieved context에 포함된 비율 | 검색 누락 ↓ |

각 메트릭은 0~1 정규화. 평균값으로 단계별 효과를 비교.

## 2. 왜 ragas 라이브러리 대신 자체 구현

- 본 프로젝트는 **로컬 Ollama 단일 의존**(`ibm/granite4`, `qwen3` 등)으로 통일. ragas는 LangChain/OpenAI 키 기본 가정이라 결합도가 높다.
- 메트릭 4개는 모두 "LLM judge로 atomic claim 분해 + 지지여부 판정"이라 프롬프트 4개로 충분.
- 같은 judge(Ollama)로 통일하면 평가 결과의 **결정성**(temperature=0)도 확보.

## 3. 사용

```bash
# 기본(golden_vehicle.json, 메타 필터 없음)
python3 eval/run_ragas.py

# 차종 메타 필터 효과 측정 — 1단계가 안 켜진 경우 vs 켜진 경우 비교
python3 eval/run_ragas.py --car tucson --powertrain hybrid

# judge 모델 교체
JUDGE_MODEL=qwen3:8b python3 eval/run_ragas.py
```

출력 예(가상):
```
=== RAGAS-lite (5 cases, judge=ibm/granite4:latest) ===

id                       faith  ans-rel  ctx-prec  ctx-rec
------------------------------------------------------------
veh-tpms                  0.86     0.75      0.80     0.83
veh-checkengine           0.71     0.75      0.60     0.67
veh-dtc-p0420             1.00     1.00      0.80     1.00
…
------------------------------------------------------------
avg                       0.82     0.80      0.72     0.77
```

## 4. 인사이트 가설(2~3단계와 어떻게 묶이나)

각 단계가 어느 메트릭을 가장 끌어올려야 하는지:

| 단계 | 주요 영향 메트릭 | 가설 |
|---|---|---|
| 1단계 — 메타 1차 필터 | **context_precision** ↑ | 무관 차종 청크가 후보에서 빠지면 precision이 가장 크게 오름. recall은 1단계로 의미있게 변하진 않음(정답 청크는 메타 매칭 → 살아남음). |
| 2단계 — 하이브리드(BM25 한글 토큰화) | **context_recall** ↑ | 벡터가 놓친 키워드 일치 청크(예: 정확한 부품명·DTC 코드)를 BM25가 잡아줘 recall↑. |
| 3단계 — 크로스인코더 리랭커 | **faithfulness, answer_relevance** ↑ | top-K 안의 순서가 정확해져 LLM이 가장 관련 있는 컨텍스트를 인용 → 환각 ↓. |

이 가설을 매 단계마다 RAGAS로 검증하고 **단계별 개선폭**을 표로 적재.

## 5. 한계 — 정확히 알아두기

- LLM judge는 결정적(temperature=0)이라도 모델·프롬프트에 따라 절대값이 달라진다. **절대점수보다 같은 judge로 단계별 delta**가 의미 있다.
- atomic claim 분해는 모델이 작을수록 부정확. judge는 7B 이상 권장.
- 매뉴얼 KB는 차종별 chunk가 분리돼 있어 expected의 일부 클레임이 여러 청크에 분산될 수 있음 — context_recall 측정 시 candidate 수(`TOP_K=2`)를 늘려서 비교하면 유의미.

## 6. 로드맵에서의 위치

이 RAGAS 하네스는 **단계가 아니라 측정 레이어** — 1·2·3단계 어느 시점에도 돌려서 단계별 delta를 만든다. 측정 → 개선 → 측정의 사이클이 본 작업의 핵심.

`docs/RAG-ACCURACY-ROADMAP.md` 와 함께 본다.

## 7. 테스트 가이드라인 — "어떻게·왜" 측정하나

### 7.1 측정 대상 = 한 변수 한 번에

각 측정은 **한 가지 변경**의 효과만 잡아야 의미가 있다. 동시에 두 가지를 바꾸면 어느 쪽이 효과를 줬는지 모른다. 권장 측정 순서:

| 차수 | 변경(직전 대비) | 비교 baseline | 기대 메트릭 |
|---|---|---|---|
| T0 | (없음 — 현 상태) | — | 모든 메트릭의 절대값 baseline |
| T1 | **앱 재기동** (1·2단계 코드 활성, 백필 잡으로 메타 채움) | T0 | context_precision 변화는 미미(메타 필터 OFF 상태) — 회귀 없는지만 확인 |
| T2 | **메타 필터 ON** (`--car ioniq5`) | T1 | **context_precision ↑**, 나머지 거의 동일 |
| T3 | **hybrid ON** (BM25 한글 토큰화 활성) | T2 | **context_recall ↑** (벡터가 놓친 키워드 매칭) |
| T4 | **+ 리랭커** (3단계 도입 시) | T3 | **faithfulness, answer_relevance ↑** |

T1~T3는 같은 KB·같은 judge·같은 골든셋. T2가 의미있게 오르지 않으면 메타 매칭이 안 됐다는 신호(파일명 파서 회귀 의심). T3가 의미있게 오르지 않으면 토크나이저가 여전히 망가졌거나(vocab 로그 확인) 골든셋이 키워드 매칭 케이스를 포함하지 않은 것.

### 7.2 측정 전 체크리스트(전제 조건)

- [ ] 앱이 떠 있고 `GET /api/data/count?namespace=vehicle`가 0이 아님 — KB가 비면 측정 무의미
- [ ] Ollama가 뜨 있고 `JUDGE_MODEL` 모델이 `ollama list`에 있음 — 없으면 judge가 전부 ERR 반환
- [ ] 골든셋(`eval/golden_vehicle.json`)에 적어도 `question` + `expectAnswer` 있는 케이스 — `expectAnswer` 없는 케이스는 context_recall 측정 불가(`-`로 표시)
- [ ] 토크나이저 회귀 감지 — 앱 로그에 `[KeywordIndex] ns='vehicle' docs=N vocab=K avgTokens=X` 출력. **vocab이 영문/숫자만 갯수(예: 수백)면 한글 토큰화 망가짐**. 정상이면 어휘 수천~만.

### 7.3 실행

```bash
# T0/T1 베이스라인 (필터 없음)
python3 eval/run_ragas.py | tee eval/ragas_T1.txt

# T2 메타 필터 ON
python3 eval/run_ragas.py --car ioniq5 | tee eval/ragas_T2_ioniq5.txt

# T2' 다른 차종으로도 — 메타 필터가 일관적으로 동작하는지
python3 eval/run_ragas.py --car ioniq5_n | tee eval/ragas_T2_ioniq5n.txt

# T3 hybrid 효과는 hybrid 자체가 서버 설정이라 application.yaml 의
# retrieval.hybrid.enabled=true|false 토글 후 앱 재기동해서 비교
# (현재는 기본 true. 직접 EVAL-only 오버라이드는 평가 보안상 평소엔 꺼져 있음)

# judge 모델 교체 — 절대값은 모델마다 달라도 단계별 delta는 유지돼야
JUDGE_MODEL=qwen3:8b python3 eval/run_ragas.py | tee eval/ragas_T1_qwen.txt
```

### 7.4 결과 읽기 — 무엇이 좋고 나쁜가

| 메트릭 | "좋음" | "의심" 신호 |
|---|---|---|
| faithfulness | ≥ 0.80 | < 0.50 — 컨텍스트에 없는 내용을 답이 만들어내고 있음(환각). 프롬프트 강화 또는 컨텍스트 양 늘리기 |
| answer_relevance | ≥ 0.75 | < 0.50 — 답이 질문을 회피·우회. 답 생성 모델 교체 또는 프롬프트 점검 |
| context_precision | ≥ 0.70 | < 0.50 — 무관 청크가 top-K 진입. **1단계 메타 필터의 주 효과 지점** |
| context_recall | ≥ 0.70 | < 0.50 — 정답 청크가 top-K 진입 못 함. **2단계 BM25/한글 토큰화의 주 효과 지점**, 또는 `TOP_K`를 늘려야 함 |

**절대점수의 함정**: judge 모델·프롬프트·온도에 따라 절대값은 ±0.1 흔들린다. **같은 judge로 측정한 단계별 delta**가 의미 있다. 한 번에 한 변수만 바꾼다는 §7.1 원칙이 결국 이걸 보장.

### 7.5 왜 ragas 라이브러리 대신 자체 구현했나

- 본 프로젝트는 로컬 Ollama 단일 의존(LangChain·OpenAI 없음). ragas의 LangChain 결합도가 비용 대비 부담.
- 4 메트릭은 모두 "LLM judge로 atomic claim 분해 + 지지 여부 판정"이라 프롬프트 4개로 등가 구현 가능.
- 같은 judge(Ollama, temperature=0)로 통일하면 결정성 확보 + 단계별 delta의 일관성 보장.

### 7.6 한계 — 측정자가 알아둘 것

- judge가 작을수록(7B 미만) atomic 분해가 부정확해 faithfulness/recall이 들쭉날쭉. **7B+ 권장**.
- 매뉴얼 청크가 차종별 분리돼 있어 정답 정보가 여러 청크에 분산될 수 있음. context_recall 측정 시 검색 후보를 늘리는 것이 공정한 비교.
- 비용: 5케이스 × 4메트릭 × 각 메트릭당 3~10회 judge 호출 = 케이스당 ~30회 LLM 호출. 작은 모델로도 수분 소요. **CI에 매번 돌리진 말고 단계 게이트에서만**.

### 7.7 측정 결과 적재 규약

매 측정 결과는 이 문서 §"인사이트" 하위에 표로 누적:

```
| 시점 | 변경 | judge | 케이스 | faith | rel | prec | rec | 메모 |
|---|---|---|---|---|---|---|---|---|
| T1 | (baseline) | granite4 | 5 | … | … | … | … | … |
| T2 | 메타 필터 ON (car=ioniq5) | granite4 | 5 | … | … | … | … | … |
```

원본 출력은 `eval/ragas_T*.txt` 로 보존. 단계 진입 결정의 근거.

## 8. 인사이트 (측정 결과 누적)

### T0 — 베이스라인 (2026-06-24)

**환경**: 현재 구동 중인 앱(1·2단계 코드 적용 전), `namespace=vehicle`, KB = 2 매뉴얼(ioniq5 NE1 366청크 + ioniq5_n NE1N 12청크), judge=`ibm/granite4:latest`, 골든=`eval/golden_vehicle.json` 5케이스.

| 시점 | 변경 | judge | 케이스 | faith | rel | prec | rec |
|---|---|---|---|---|---|---|---|
| **T0** | (baseline, 구버전 앱) | granite4 | 5 | **0.52** | **0.30** | **0.60** | **1.00** |

케이스별:
| id | faith | rel | prec | rec |
|---|---|---|---|---|
| veh-tpms        | 1.00 | 0.50 | 1.00 | 1.00 |
| veh-checkengine | 0.00 | 0.00 | 1.00 | 1.00 |
| veh-dtc-p0420   | 0.00 | 0.00 | 0.00 | 1.00 |
| veh-ev-soc-soh  | 0.60 | 0.50 | 0.00 | 1.00 |
| veh-scc         | 1.00 | 0.50 | 1.00 | 1.00 |

#### 해석

- **context_recall = 1.00 (완벽)** — 정답 핵심 주장이 검색된 컨텍스트에 다 들어 있다. KB가 작아도 골든셋이 적재 매뉴얼 범위 안이라는 뜻. 단계 2(BM25 한글 fix)의 효과는 이 KB로는 안 보일 수 있음 — 더 큰 KB(다른 차종 추가) 또는 더 까다로운 골든셋 필요.
- **context_precision = 0.60** — 5 중 2케이스가 0.00. 둘 다 **현 KB 범위 밖**:
  - `veh-dtc-p0420`: 가솔린 엔진 촉매 DTC. KB의 ioniq5는 EV라 P0420 챕터 자체가 없음.
  - `veh-ev-soc-soh`: judge가 "SOC/SOH" 정확 매칭을 강하게 봐 매뉴얼 표현("배터리 잔여 용량")을 무관 판정.
  - → **1단계 메타 필터의 효과를 보려면 차종 더 적재 후 비교** 필요(현 KB는 ioniq5뿐이라 필터링할 후보 자체가 없음).
- **faithfulness = 0.52** — 답변 진술의 절반이 컨텍스트로 지지 안 됨. **환각 위험 시그널**. 가능 원인:
  - veh-checkengine, veh-dtc-p0420 = 0.00 — KB에 없는 내용을 답이 끌어왔다(모델 사전지식 사용). 프롬프트에서 "컨텍스트에 없으면 모른다고 답하라" 강화 필요.
  - 1·2·3단계 모두 환각 자체를 직접 잡진 않음 — 별도 작업(프롬프트 강화 또는 컨텍스트 양 늘리기).
- **answer_relevance = 0.30 (낮음)** — 답이 질문을 직접 안 받음. 답이 짧거나 우회. judge가 너무 까다로울 가능성 — judge 모델 교체(`qwen3:8b`)로 절대값 sanity check 권장.

#### 다음 측정 결정

T0의 가장 큰 신호는:
1. **context_recall은 이미 천장(1.00)** → 2단계(BM25)는 이 KB로는 측정 무의미. 더 큰 KB 적재 후 측정해야 효과 보임.
2. **context_precision 효과를 보려면 다른 차종 매뉴얼이 KB에 들어와야 함** (현재 ioniq5만 → 필터링할 게 없음).
3. **faithfulness/relevance가 낮음**은 KB 범위 밖 질문(P0420, EV SOC/SOH) 때문 — 골든셋이 KB 적재 매뉴얼에 맞춰져야 공정한 측정.

→ **T1(앱 재기동, 백필 잡 + 한글 토크나이저)** 측정은 의미 있는 비교가 가능하나, **T2(메타 필터) / T3(BM25)는 매뉴얼 추가 적재 후 측정 권장**. 또는 골든셋을 ioniq5 범위 안으로 좁혀 재실행.

원본 출력: 백그라운드 잡 `bfixiekrr` (확보됨, 본 표에 정리). 보존: `eval/ragas_T0_baseline.txt`.

### T0' — ioniq5-narrowed 골든셋 (대기 중)

골든셋을 ioniq5 적재 범위 안으로 좁힌 [`eval/golden_vehicle_ioniq5.json`](../eval/golden_vehicle_ioniq5.json)(6 케이스: TPMS·V2L·고전압 충전·회생제동·프리컨디셔닝·SCC) 준비 완료. **시도 시점에 앱이 :8080에서 응답 없음(Connection refused)으로 측정 보류**. 앱 재기동 후 1회 실행:

```bash
python3 eval/run_ragas.py --golden eval/golden_vehicle_ioniq5.json | tee eval/ragas_T0_ioniq5.txt
```

기대: KB 범위 안 질문만 있으므로 T0(0.52/0.30/0.60/1.00) 대비 faithfulness·answer_relevance가 의미있게 오를 것(환각 케이스 제거). 결과 확보 후 본 섹션에 행 추가.

### T0-재측정 — 3-judge 삼각검증 (2026-07-07, KB 124문서/38,545청크)

**환경**: KB가 T0의 2매뉴얼에서 **124문서/38,545청크로 확장**된 상태, `golden_vehicle.json` 5케이스(T0와 동일), 같은 질문을 **judge 3개**로 교차 측정. `pgvector` 저장, `LLM_PROVIDER=ollama`.

| judge | 종류 | faith | ans-rel | ctx-prec | ctx-rec |
|---|---|---|---|---|---|
| **qwen3:8b** | 강함 | **0.50** | 0.50 | 0.30 | 0.23 |
| **ibm/granite4:latest** | 강함 | **0.53** | 0.60 | 0.50 | 0.89 |
| vehicle-qwen2.5-1.5b (FT) | 약함 | 1.00 | 0.80 | 0.60 | 0.96 |

원본: `eval/ragas_T0remeasure_{qwen3,granite4,ft}.txt`.

#### 해석 — 이 측정의 핵심은 "숫자"가 아니라 "judge를 못 믿는 법을 안다"

1. **강한 judge 둘이 faithfulness에서 일치(0.50 ↔ 0.53)** → 신뢰 가능한 신호. 게다가 T0(0.52)와도 같다. **KB를 2매뉴얼→124문서로 키웠는데도 faith가 안 올랐다** = 충실도 갭은 KB 크기 문제가 아니라 **실재하는 grounding 갭**(답변 주장의 ~절반만 컨텍스트로 지지). 정직하게 인정할 부분.
2. **1.5B FT judge만 1.00** → 약한 judge가 전부 "충실함"으로 도장(변별력 0). judge 3개로 이제 **"소형 모델은 judge로 못 쓴다"가 데이터로 증명**됨. LLM-as-judge의 대표 함정.
3. **강한 judge끼리도 context 메트릭은 크게 갈림** — 특히 **context_recall: qwen3 0.23 vs granite4 0.89**. → **judge 분산은 메트릭마다 다르다**: faithfulness는 안정(0.50↔0.53), context 메트릭은 노이즈 큼. 따라서 **faithfulness를 대표 지표로, context-recall은 참고만** 쓰는 게 정직.
4. **케이스별 실패가 드러남(개선 타깃)**: `veh-dtc-p0420`은 **모든 judge에서 0.00**, `veh-ev-soc-soh`도 강한 judge에서 faith 0.00. → DTC 코드·EV SOC/SOH 주제에서 retrieval이 무너짐(그 주제가 KB 커버리지 밖이거나 검색이 놓침). 개선 방향: `TOP_K`↑(현 2) · 청킹 · rerank.

#### 결론 (신뢰 규약)
- **대표 지표 = faithfulness ≈ 0.5** (강한 judge 2개 합의, T0와도 일치).
- **context-recall은 judge 의존이 커** 단일 값으로 인용 금지 — 강한 judge 다수의 합의만 인용.
- **judge는 반드시 7B+ 강한 모델.** 1.5B judge의 1.00은 버린다.
- 다음 개선의 정량 목표: faithfulness 0.5 → 0.8 (retrieval 품질: TOP_K·rerank·청킹).

### T2 — 메타 필터 ON (`car=tucson, powertrain=hybrid`, 2026-07-07)

같은 5케이스에 **1단계 메타 필터**를 걸어 재측정. 필터의 타깃 지표는 **context_precision**(무관 청크 제거).

| judge | faith | ans-rel | ctx-prec | ctx-rec |
|---|---|---|---|---|
| qwen3:8b (강함) | 0.37 | 0.45 | 0.30 | 0.50 |
| granite4 (강함) | 0.33 | 0.40 | **0.80** | 0.79 |
| vehicle-1.5b (약함) | 0.85 | 0.70 | 0.60 | 0.92 |

비교 대상 = T0-재측정(필터 OFF): qwen3 prec 0.30 / granite4 prec **0.50**.

#### 해석 — "필터가 나쁘다"로 오독하면 안 되는 이유

1. **필터의 의도된 효과가 granite4에서 실측됨**: context_precision **0.50 → 0.80** ↑. tucson 무관 청크를 걸러 정밀도가 올랐다 = **메타 필터가 설계대로 동작**. (qwen3는 0.30 그대로 — 같은 지표에서도 강한 judge끼리 갈림 = judge 분산 재확인.)
2. **그런데 faithfulness는 떨어짐(0.5→0.37/0.33)** — 이건 **필터 결함이 아니라 실험 설계 불일치**다. 골든셋은 *generic* 질문(TPMS·P0420·**EV** SOC/SOH·SCC)인데 필터는 *tucson 하이브리드*로 좁혔다. 예: EV 질문(`veh-ev-soc-soh`)을 하이브리드로 필터하면 맞는 컨텍스트가 사라져 답이 근거를 못 얻는다 → faith↓. **"질문 범위 ≠ 필터 범위"라 부당한 비교**. (T0 해석에서 이미 경고한 함정.)
3. **공정한 필터 실험 설계(한 변수만)**: 골든 질문을 *tucson 하이브리드에 관한 것*으로 맞춘 뒤 **필터 ON vs OFF**를 비교해야, faith를 훼손하지 않고 precision 효과만 분리해 측정할 수 있다.

#### 결론
- **필터의 precision 상승은 실재**(granite4 0.50→0.80)하나, **generic 골든셋 + 차종 필터 = 부당 조합**이라 faith 하락은 필터 탓이 아니다.
- **측정 규약 추가**: 메타 필터 A/B는 **골든셋의 차종·파워트레인을 필터와 일치**시킨 상태에서만 유효하다(§7.1 "한 변수" 원칙의 구체화). 불일치 조합의 숫자는 인용 금지.
- 면접 한 줄: *"메타 필터가 precision을 0.5→0.8로 올린 건 확인했지만, generic 골든셋에 차종 필터를 걸면 faith가 떨어지는 부당 비교가 된다는 걸 알고, 필터 실험은 골든 범위를 필터에 맞춰야 공정하다고 규약화했다."*

### T-rerank 기준선 — 10문항 골든셋 · qwen3 고정 judge (2026-07-07)

**환경**: `golden_vehicle.json` **10문항**(KB 실재 매뉴얼 기준으로 확장), judge=**qwen3:8b 고정**(3-judge 삼각검증에서 가장 변별력 있어 채택), rerank=`mmr`(현 기본), 캐시 우회(`--rerank mmr`). 리랭커 A/B의 **기준선**이다.

| 지표 | avg | 채점된 문항 |
|---|---|---|
| faithfulness | 1.00 | 7/10 (3개는 주장 추출 없음=`-`) |
| answer_relevance | 0.72 | 9/10 |
| **ctx-precision** | **0.70** | 10/10 — **리랭커 판정 핵심 지표** |
| ctx-recall | 0.55 | 10/10 |

문항별 ctx-precision: tpms·scc·brake·jumpstart·smartkey **1.00**(천장), engine-oil **0.00**, isofix·tire-rotation·kona-ev·hybrid-regen **0.50**.

**해석**: 강한 5문항은 이미 1.00이라 리랭커가 더 올릴 여지 없음 → **cross-sidecar의 delta는 약한 5문항(engine-oil·isofix·tire·kona-ev·hybrid)에서만 갈린다.** 진단(`RAG-ACCURACY-ROADMAP §진단`)에서 정답 문서가 후보 풀엔 있으나 TOP_K=2 밖으로 밀린 정밀도 문제로 확인됐으므로, 크로스인코더가 그 순위를 교정하면 이 5문항의 precision이 올라야 한다.

**A/B 절차·판정**: [`GUIDE-reranker-eval.md`](GUIDE-reranker-eval.md). `cross-sidecar`의 ctx-precision avg가 이 기준선(0.70)을 유의미하게 넘으면 채택, 아니면 mmr 유지(지연 비용 대비 이득 없음). 원본: `eval/ragas_qwen3_mmr.txt`.

### T-rerank A/B — mmr vs cross-sidecar (2026-07-07, 단일 실행)

| 지표 | mmr | cross-sidecar | delta |
|---|---|---|---|
| **ctx-precision** | **0.70** | **0.80** | **+0.10** ⭐ |
| faithfulness | 1.00 | 0.94 | −0.06 |
| answer_relevance | 0.72 | 0.72 | 0 |
| ctx-recall | 0.55 | 0.59 | +0.04 |

문항별 ctx-precision(mmr→cross):

| 문항 | mmr→cross | |
|---|---|---|
| engine-oil | 0.00→**1.00** | ✅ 大 |
| isofix | 0.50→**1.00** | ✅ |
| hybrid-regen | 0.50→**1.00** | ✅ |
| tire-rotation | 0.50→**0.00** | ❌ 후퇴 |
| kona-ev-charging | 0.50→**0.00** | ❌ 후퇴 |
| tpms·scc·brake·jumpstart·smartkey | 1.00→1.00 | = 천장 |

#### 결론 (정직하게)
- **핵심 지표 ctx-precision +0.10(0.70→0.80) = 리랭커가 올린 실제 방향.** 약한 5문항 중 3개(engine-oil·isofix·hybrid)를
  크게 교정 — 진단대로 후보 풀엔 있으나 순위에 밀린 정답을 크로스인코더가 top-2로 끌어올린 게 작동.
- **그러나 혼재**: tire·kona-ev는 0.50→0.00 **후퇴**. 특히 **kona-ev는 우리가 고치려던 타깃인데 악화** — 크로스인코더가
  그 KB의 kona_electric 청크보다 다른 충전 문단(ioniq5)을 더 관련 있다고 판단. **골든 기대("kona_electric이어야")가
  지나치게 엄격**할 가능성(ioniq5 충전 답도 정답으로 볼 수 있음).
- **채택 보류 — 재측정 필요**: (1) TOP_K=2라 문항당 0/0.5/1.0 3단계뿐 + qwen3 judge 확률적 → **+0.10이 노이즈인지
  2~3회 반복으로 확정**해야. (2) faithfulness −0.06도 확인. (3) **TOP_K=2→3~4 확대**가 후퇴 2건을 살릴 가능성(슬롯↑).
- 면접 한 줄: *"크로스인코더가 ctx-precision을 0.70→0.80으로 올렸지만, 2문항 후퇴와 단일 실행·거친 눈금 때문에
  바로 채택하지 않고 반복 측정과 TOP_K 확대를 조건으로 걸었다 — 측정 없이 최적화하지 않는다."*

원본: `eval/ragas_qwen3_mmr.txt`, `eval/ragas_qwen3_cross.txt`.


