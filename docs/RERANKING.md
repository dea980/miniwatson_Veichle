# Reranking (재정렬)

1차 벡터 검색으로 넉넉히 후보를 뽑은 뒤, 더 정밀한 기준으로 재점수해 최종 top-K만 LLM에 넘기는 단계. 이 문서는 MiniWatson에서 실제로 구현하고 측정한 내용만 기록한다.

## 1. 왜 필요한가

벡터 유사도(cosine)는 "대충 비슷"은 잘 잡지만 "질문 의도에 진짜 맞는지"는 약하다. 정답 청크가 유사도 순위 3등, 5등에 있을 수 있는데 바로 top-2만 자르면 놓친다(recall 손실).

그래서 2단계로 나눈다.

```
질의 -> 1차 검색(top-N, 예: 20) -> rerank(재점수) -> top-K(2~3) -> LLM
         recall 확보(넓게 건짐)      precision 확보(정밀하게 좁힘)
```

- FETCH_N = 20 : 1차 벡터 검색이 뽑는 후보군
- TOP_K = 2 : rerank 후 LLM에 최종 전달

RagService는 vectorIndex.search(ns, q, FETCH_N)로 후보를 뽑고, reranker.rerank(question, candidates, TOP_K)로 좁힌다.

## 2. 구현 (전략 패턴)

Chunker와 동일하게 인터페이스 + 구현체 + 설정 전환 구조.

```
Reranker (interface)
 ├─ NoopReranker  @Component("none")   1차 검색 top-K 그대로 (비교 기준선)
 ├─ LlmReranker   @Component("llm")    LLM에 후보를 주고 관련 순서로 고르게 함
 └─ MmrReranker   @Component("mmr")    관련도 + 다양성으로 재점수
```

(cross-encoder는 이후 추가 예정)

IngestionService의 Chunker와 같은 방식으로 주입한다.

```yaml
rerank:
  strategy: llm     # none | llm | mmr
```

```java
this.reranker = rerankers.getOrDefault(strategy, rerankers.get("llm"));
```

### 2.1 NoopReranker (기준선)

1차 검색 결과 top-K를 그대로 반환한다. rerank를 끈 상태를 재현해 before/after 비교의 기준으로 쓴다.

### 2.2 LlmReranker (listwise)

후보 전체를 한 프롬프트에 번호와 함께 나열하고, LLM에게 가장 관련된 번호만 순서대로 받는다.

- listwise : LLM 호출 1회로 전체를 한 번에 재정렬 (후보마다 부르지 않음)
- 후보당 300자 제한 : 20개 x 전문이면 프롬프트가 폭발하므로 잘라 넣는다. 관련도 판단엔 앞부분으로 충분
- 견고 파싱 : 응답에서 번호만 정규식으로 추출, 범위 밖/중복 제거, 실패 시 상위 top-K 폴백

### 2.3 MmrReranker (Maximal Marginal Relevance)

관련도만 보던 것을 관련도 + 다양성으로 재점수한다. 1차 top-N에 거의 같은 내용의 청크가 여러 개 끼면 그냥 top-K를 자를 때 중복만 LLM에 가서 컨텍스트가 낭비된다. MMR은 이미 고른 것과 너무 비슷한 후보를 감점해 서로 다른 측면의 청크를 뽑는다.

```
MMR score = lambda * (질의 관련도) - (1 - lambda) * (이미 선택된 것들과의 최대 유사도)
```

- lambda = 0.6 : 관련도를 약간 우선하면서 다양성도 반영
- 질의 관련도는 1차 검색 순위(rank)로 근사한다. 후보가 이미 유사도순으로 오므로 질의 임베딩을 다시 계산하지 않는다. 비용 0
- 청크 간 유사도는 Article에 저장된 768-dim 임베딩끼리 cosine으로 계산

## 3. 실측 비교 (namespace: IBM 리포트 PDF 101청크)

### 3.1 쉬운 질문 - 차이 없음

질문: "What digital twin capability do energy companies value most?"

| 전략 | sources |
|---|---|
| none | #32, #26 |
| llm | #32, #29 |

둘 다 정답 청크 #32("57% energy organizations ... optimization recommendations")를 1등으로 잡았다. 질문 단어가 정답 청크에 거의 그대로 있어 1차 검색만으로 충분했고, rerank는 2등만 바꿨다. 답도 둘 다 정확("optimization recommendations").

### 3.2 까다로운 질문 - 차이 발생

질문: "Why do companies struggle to get value from their AI investments?" (본문에 "silos"라는 단어 없이 의도로 물음)

| 전략 | sources | 답변 초점 |
|---|---|---|
| none | #3, #24 | "siloed functions block value, 82%" (구조가 문제다, 일반론) |
| llm | #2, #25 | "fragmentation/disconnected workflows가 biggest constraint" (직접 인과) |

rerank가 #3 대신 #2를 1등으로 끌어올렸다. #2는 "Fragmentation - not talent or AI maturity - is the biggest constraint: 82%..."로, "왜 struggle하는가"라는 질문 의도에 더 직접적인 인과를 준다. 벡터 유사도는 #3과 #2를 비슷하게 보지만, 질문 의도까지 고려하면 #2가 낫다는 판단을 LLM이 한 것이다. 1차 검색만으로는 못 하는 일이다.

### 3.3 MMR 다양성 효과

질문: "What are agentic workflows and how do they work?" (본문에 workflow 관련 유사 청크가 많음)

| 전략 | sources | 2등 청크 |
|---|---|---|
| none | #38, #45 | #45 - #38과 같은 obstacle/redesign 맥락 |
| mmr | #38, #31 | #31 - Cross-workflow impact, 다른 측면 |

둘 다 #38을 1등으로 유지하되 2등이 바뀌었다. MMR이 #38과 덜 겹치는 #31을 끌어올리고, #38과 맥락이 비슷한 #45를 밀어냈다. 유사도만 보면 #45가 2등이지만, 다양성 패널티가 걸려 서로 다른 관점의 청크 조합이 됐다.

참고로 3.2의 "struggle" 질문에 mmr을 돌리면 none과 동일한 #3, #24가 나왔다. #3과 #24가 이미 서로 다른 청크라 다양성 패널티가 걸릴 게 없었기 때문이다. MMR은 후보에 중복이 있을 때만 효과가 난다.

## 4. 통찰

- rerank의 이득은 1차 검색이 약할 때 커진다. 검색이 이미 강하면(좋은 임베더 + 잘 쪼갠 청크) 정답이 이미 top에 있어 rerank가 바꿀 게 적다. MiniWatson은 nomic + recursive 청킹이 잘 동작해 쉬운 질문에서는 차이가 작았다.
- 차이는 질문 단어와 본문 표현이 어긋나는(어휘 불일치) 질문, 여러 청크가 비슷하게 매칭되는 질문에서 분명해진다.
- 비용: LLM rerank는 답변 생성과 별개로 LLM 호출이 1회 추가된다. 검색 1 + rerank 1 + 생성 1. 품질 이득과 지연을 저울질해야 한다.

## 5. 다음 단계

- MMR(C). 유사도 + 다양성으로 재점수. 중복 청크를 줄여 컨텍스트 다양성 확보. 비용 0(계산만)
- cross-encoder(A). ONNX Runtime + BGE-reranker 류 전용 모델. 품질 최고지만 셋업이 무겁고, LLM rerank보다 빠르고 정확. "B는 느려서 전용 reranker로 교체"라는 고도화 경로
