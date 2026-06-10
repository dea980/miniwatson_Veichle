# Evaluation Harness (검색 품질 측정)

chunking / reranking / hybrid를 "느낌"이 아니라 수치로 비교하기 위한 평가 도구. 작은 정답셋(golden set)에 대해 retrieval recall을 측정한다. "측정 없이 최적화 없다."

## 1. 구성

```
eval/
├── golden.json     # 질문 + 기대 결과 (정답셋)
└── run_eval.py     # /api/rag/ask 호출 → sources 검사 → recall 출력
```

앱 코드가 아니라 외부 보조 스크립트다. 표준 라이브러리만 쓰므로 `python3`만 있으면 된다(설치 불필요). IDE가 모듈을 못 찾는다고 표시해도 터미널 실행은 정상이다.

## 2. 정답셋 (golden.json)

각 케이스는 질문 + namespace + 기대 조건이다. 청크 id를 직접 박지 않고 내용으로 매칭해 유연하게 둔다.

- `expectTitleContains` — 이 문자열을 제목/내용에 포함한 source가 나와야 함
- `expectKeywords` — 나열한 키워드가 **모두**(AND) 검색된 sources에 있어야 함

정답셋은 결과에 맞춰 느슨하게 바꾸지 않는다(끼워맞추기 방지). 정답셋은 고정하고, 설정을 바꿔 어느 조합이 MISS를 줄이는지 본다.

## 3. 실행

```bash
# 현재 설정으로
python3 eval/run_eval.py

# 라벨 붙여서 (조합 비교 기록용)
LABEL="recursive+llm+hybrid" python3 eval/run_eval.py

# API 주소 지정
API=http://localhost:8080 python3 eval/run_eval.py
```

출력: 케이스별 HIT/MISS + 검색된 sources + 전체 recall(%).

## 4. 설정 조합 비교

검색 시점 파라미터(rerank, hybrid)는 재시작 없이 요청별로 오버라이드할 수 있다(아래 5절). chunking은 인덱싱 시점이라 바꾸려면 재인덱싱이 필요하다.

| 축 | 값 | 변경 시점 |
|---|---|---|
| rerank | none / llm / mmr / cross | 요청별 (재시작 0) |
| hybrid | on / off | 요청별 (재시작 0) |
| chunking | fixed / recursive / semantic | 재인덱싱 필요 |

### 측정 결과 (recursive 청킹, 8 cases, 재시작 0으로 1회 실행)

초기 실행은 6/8 (75%)였고 MISS는 silos, interconnected. 두 MISS의 성격을 구분해 보니 하나는 정답셋 문제, 하나는 진짜 검색 문제였다.

- silos: 답변은 정확했다("functional silos block value"). 정답셋이 `["silos","fragmentation"]`를 AND로 요구했는데 두 단어가 다른 청크에 있어 top-2로 못 담았다. 검색 실패가 아니라 정답셋 과엄격. 핵심어 `["silos"]`로 바로잡았다.

### 중요한 함정: 측정 변수가 실제로 적용되는지부터 검증하라

처음 조합 비교에서 네 조합이 전부 동일한 결과(75%, 이후 88%)였다. "후처리 이득 0"이라고 결론냈는데 — 사실은 **요청별 rerank/hybrid 오버라이드가 RagService까지 배선되지 않아, 4조합이 전부 같은 기본 설정으로 돌고 있었다.** 같은 걸 4번 측정한 셈.

오버라이드를 끝까지 엮은 뒤(AskRequest -> RagController -> RagService -> HybridRetriever, evalOverrides 게이트) 재측정하니 조합이 실제로 갈렸다. (ill-posed였던 interconnected를 변별력 있는 dismantling-boundaries로 교체한 최종 8케이스 기준:)

| config | recall | misses |
|---|---|---|
| rerank=none, hybrid=false | 8/8 (100%) | - |
| rerank=none, hybrid=true | 8/8 (100%) | - |
| rerank=llm, hybrid=true | 7/8 (88%) | pods |
| rerank=mmr, hybrid=true | 8/8 (100%) | - |

교훈: 비교 결과가 전부 동일하면 "차이가 없다"가 아니라 "변수가 안 먹는 것 아닌가"부터 의심한다. 측정 도구 자체의 검증이 먼저다.

### 발견 1: LLM rerank가 recall을 깎을 수 있다

none/mmr은 8/8(100%)인데 llm만 7/8로 떨어졌다(pods 탈락). LLM 재정렬이 정답이던 pods 청크를 top-2 밖으로 밀어냈다. rerank는 항상 +가 아니다 — 1차 검색이 이미 맞게 뽑은 결과를 후처리가 망칠 수 있다. 이 코퍼스에선 best 조합이 단순 벡터(none) 또는 mmr이고, llm rerank는 역효과였다. "무조건 rerank를 붙이지 말고 측정해서 판단한다"의 근거.

### 발견 2: ubiquitous-term query는 retrieval로 못 잡는다 (interconnected)

interconnected("What is the interconnected enterprise?")는 모든 조합에서 MISS였다. 본문 정확 표현("agentic workflows spanning functional domains")으로 직접 검색해도 정의 청크가 안 떴다.

원인: 이 문서는 101청크 전체가 agentic workflows 주제다. "agentic", "workflows", "functional" 같은 단어가 거의 모든 청크에 있어 BM25 IDF가 0에 가깝고(변별력 없음), 벡터도 모든 청크가 비슷해 정의 청크가 도드라지지 않는다. 즉 질의어가 코퍼스 전체에 깔려 있으면(저 IDF + 낮은 의미 대비) rerank/hybrid/쿼리재작성 무엇으로도 그 청크를 집어낼 수 없다. "문서 전체가 답"인 질문은 retrieval로 답할 종류가 아니다.

이 케이스는 평가용으로 부적절했으므로, 더 변별력 있는 질문(dismantling-boundaries, "61%"라는 고유 수치)으로 교체했다. interconnected를 억지로 통과시키지 않고, 왜 부적절했는지를 기록으로 남긴다.

### 통찰

이 코퍼스에서 1차 검색은 이미 강해서 rerank/hybrid의 한계 이득이 작거나 오히려 음수(llm)였다. 후처리 기법은 1차 검색이 약하거나 코퍼스가 크고 노이즈가 많을 때 가치가 커진다. 그리고 어떤 질의(ubiquitous-term)는 후처리 이전에 retrieval 자체의 한계다.

주의: cross는 인텔 맥에서 네이티브 부재로 폴백되므로(see RERANKING.md) 비교에서 제외했다.

## 5. EVAL-ONLY: 요청별 오버라이드 (프로덕션 전 제거)

평가를 재시작 없이 빠르게 돌리려고, `/api/rag/ask`가 요청 본문으로 `rerank`/`hybrid`를 받아 그 요청에만 적용하도록 했다.

```json
{ "question": "...", "namespace": "...", "rerank": "llm", "hybrid": false }
```

이 오버라이드는 **평가 전용**이다. 프로덕션에서 외부 요청이 검색 전략을 마음대로 바꾸면 일관성·보안 문제가 된다. 그래서:

- 관련 코드에는 `// EVAL-ONLY` 주석을 달았다. 정리할 때 한 번에 찾는다:
  ```bash
  grep -rn "EVAL-ONLY" src/
  ```
- 프로덕션 전 처리: 오버라이드 필드/분기를 제거하거나, 평가 프로필에서만 허용하도록 잠근다(예: dev/demo 프로필 또는 내부 헤더 게이트).
- 제거해도 기본 동작은 그대로다. 오버라이드가 null이면 설정값(`rerank.strategy`, `retrieval.hybrid.enabled`)을 쓰기 때문이다.

영향 받는 위치(대략):
- `dto/AskRequest` — `rerank`, `hybrid` 필드
- `RagService.ask(...)` — 오버라이드 인자 분기
- `HybridRetriever.search(..., hybridOverride)` — 오버라이드 파라미터
- `RagController` — 요청 필드 전달

## 6. 다음 단계

- 답변 정확도(생성 품질) 측정 추가 — 답에 기대 키워드 포함 여부. 지금은 retrieval recall만.
- 조합 자동 루프 — run_eval.py가 rerank x hybrid 조합을 요청 오버라이드로 한 번에 돌려 표 출력.
- 정답셋 확장 — 케이스 수를 늘려 통계적 신뢰도 확보.
