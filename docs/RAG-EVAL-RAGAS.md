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

원본 출력: 백그라운드 잡 `bfixiekrr` (확보됨, 본 표에 정리).


