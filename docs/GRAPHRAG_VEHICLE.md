# Vehicle GraphRAG 고도화 설계서

> 자동차 도메인에서 벡터 RAG가 약한 **연결·멀티홉 질의**를 메우기 위한 경량 GraphRAG 설계.
> 기존 `reference/graphrag/`(금융 도메인, 미통합) + `automotive-glossary.json`을 엮은 vehicle용 계획.
> ⚠️ 설계서(고도화 방향). 현재 `src/` 미통합 — RAG는 벡터+BM25 하이브리드로 동작 중.
> 관련: [RAG-LANDSCAPE.md](RAG-LANDSCAPE.md) · [RAG_COMPONENT_FINETUNING.md](RAG_COMPONENT_FINETUNING.md) · [AGENT.md](AGENT.md)

---

## 1. 왜 — 벡터 RAG가 못 푸는 질문

현재 RAG(벡터+BM25)는 **특정 사실**("P0420이 뭐야")엔 강하지만 아래엔 약하다:

- **연결**: "안전벨트 관련 이슈를 **매뉴얼·리콜·불만 통틀어** 정리" — 세 소스가 *같은 부품/시스템*으로 엮여야 답이 됨.
- **멀티홉**: "프리텐셔너 결함 → 어떤 시스템과 연결 → 관련 리콜은?"
- **전역 종합**: "이 차종에서 연결고리가 가장 많은 결함 테마는?"

→ **그래프(관계)** 가 세 번째 retrieval 축으로 필요. (벡터=의미, BM25=어휘, **그래프=관계**)

## 2. 설계 — 로컬 친화 경량 그래프 (LLM 0회)

정통 GraphRAG(청크마다 LLM triple 추출)는 로컬에서 비용 폭발 + 소형 모델 노이즈. 그래서:

- **엔티티 = `automotive-glossary.json` 사전 + 규칙(DTC 정규식 `P\d{4}`, 부품·시스템 용어)** — 추출 비용 ~0, 결정적.
- **관계 = 공출현(co-occurrence)**: 같은 청크/문서에 함께 나온 엔티티를 엣지로 연결.
- 인덱싱이 임베딩 수준으로 빠르고 재현 가능 → 로컬에서 멀티홉 실현.

> 핵심: **이미 가진 자동차 약어/DTC 사전이 그대로 엔티티 사전**이 된다. (financ 레퍼런스의 `DomainGlossary`를 vehicle로 교체)

## 3. 자동차 그래프 스키마 (예)

```
[엔티티] 부품(브레이크패드·산소센서…) · 시스템(ABS·SCC·TPMS…) · DTC(P0420…) · 증상(경고등·소음…)
[엣지]   공출현:
  - 매뉴얼 청크:  "ABS" ── "휠속도센서" ── "경고등"
  - 리콜 레코드:  "PALISADE" ── "instrument cluster" ── 캠페인번호
  - 불만 레코드:  "ENGINE" ── "stall" ── 신고
→ 부품/시스템 노드를 허브로 매뉴얼·리콜·불만이 한 그래프에서 연결
```

질의 "안전벨트 이슈?" → `안전벨트/프리텐셔너` 노드 → 2-hop BFS → 연결된 매뉴얼 청크 + 리콜(22V354 등) + 불만 종합.

## 4. 아키텍처 (기존에 얹기)

```
ingest → chunk → ├ VectorStore   (의미)
                 ├ KeywordIndex  (BM25)
                 └ KnowledgeGraph (자동차 엔티티 co-occurrence)   ← 신규

ask → QueryRewriter(약어↔정식명, glossary 재사용)
     → HybridRetriever.search
        ├ vector top-N
        ├ keyword top-N
        └ graph top-N (멀티홉 BFS 깊이2, 감쇠 1/0.5/0.25)        ← 신규
        → RRF 융합 → Reranker → 프롬프트 → 생성
```

재사용: `reference/graphrag/`의 `KnowledgeGraph.java`(공출현+멀티홉 BFS), `QueryRewriter.java`, `DomainEntityExtractor.java`. **사전만 vehicle로 교체**(financ → automotive-glossary).

## 5. 통합 단계 (가벼움)

1. `reference/graphrag/{data,service}`를 `src/`로 이동.
2. `DomainGlossary` → `automotive-glossary.json` 로딩으로 교체(이미 `IngestionService`가 로드 중 → 공유).
3. `HybridRetriever`에 graph 후보 RRF 합류(3번째 소스), `IndexingService.index`에서 그래프 갱신.
4. `application.yaml`: `retrieval.graph.enabled` 토글(A/B 비교용).
5. 평가: vehicle 멀티홉 셋(`eval/golden_multihop_vehicle.json`) — "매뉴얼+리콜 통합" 질문에서 graph on/off recall 비교.

## 6. 설정 (A/B 토글)

```yaml
retrieval:
  hybrid: { enabled: true }
  graph:  { enabled: true }   # 그래프 후보 RRF 합류 on/off
```
벡터-only ↔ 하이브리드 ↔ +그래프를 같은 평가셋으로 비교(증거지표).

## 7. 기대 효과 & 한계 (정직)

- **효과**: 연결·멀티홉 질문 recall↑, 답변에 **그래프 경로**를 근거로 제시 → 설명가능성↑.
- **한계**: 공출현 그래프는 관계 "종류"를 모르고 "연결"만 안다(정밀도 < 정통 KG). 정밀 관계 필요 시 신규 문서에 한해 LLM triple 추출을 **배치/캐시**로 점진 도입.
- 인메모리 그래프 → 대용량은 Neo4j/Apache AGE로 영속화(인터페이스화 후 구현 추가).

## 8. 면접 한 줄

> "벡터·BM25로 부족한 **관계 추론**은 GraphRAG로 메운다. 단 로컬 제약상 LLM triple 추출 대신 **도메인 사전(자동차 약어·DTC) 기반 공출현 그래프**로 비용 0·결정적으로 구현하고, RRF 3번째 소스로 합류시켜 매뉴얼·리콜·불만을 부품 단위로 잇는다. on/off A/B로 효과를 정량화한다."
