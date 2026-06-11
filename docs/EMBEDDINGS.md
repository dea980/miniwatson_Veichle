# Embeddings (임베딩 모델 비교)

질의와 문서를 같은 벡터 공간으로 보내 cosine으로 검색하는 단계. RAG 파이프라인 load -> split -> embed -> store에서 embed에 해당한다. 임베딩 모델은 retrieval 품질의 1차 결정자다. 청킹·rerank·hybrid를 아무리 손봐도, 1차 검색이 정답 청크를 후보에 못 올리면 뒤 단계가 복구할 수 없다. 이 문서는 비교 방법과 측정 하네스를 기록한다. 측정값은 실행 후 채운다(아래 7절 표). 지어내지 않는다.

## 1. 왜 비교하나

임베딩 모델은 하나만 트레이드오프가 아니라 네 축이 한꺼번에 움직인다.

- 차원(dimension). 768 vs 1024는 표현력 차이지만, 동시에 저장량·연산량 차이다. 1024는 768 대비 벡터 1개당 +33% 바이트.
- 품질(recall). 모델마다 학습 데이터·목적이 달라 같은 질의에 다른 청크를 1등으로 올린다.
- 속도(ingest/query 지연). 큰 모델일수록 임베딩 1회가 느리다. 청크 수천 개를 넣는 ingest에서 누적된다.
- 다국어. 영어 중심 모델은 한국어 질의-한국어 문서 매칭이 약하다. 코퍼스에 비영어가 있으면 이 축이 변별력을 가른다.

"기본값 nomic이 최선인가"는 측정 없이 답할 수 없다. 그래서 후보 4개를 같은 코퍼스·같은 정답셋으로 돌려 비교한다.

## 2. 후보 4개 (차원별)

| 모델 | 차원 | 비고 |
|---|---|---|
| granite-embedding:30m | 384 | 가장 작고 빠름. 엣지/대량 후보 |
| nomic-embed-text | 768 | 현재 기본값 |
| granite-embedding:278m | 768 | 다국어 학습. 한국어 등 비영어 후보 |
| mxbai-embed-large | 1024 | 표현력↑, 저장·연산 비용↑ |

384/768/1024 세 차원과 영어중심 vs 다국어 축을 한 번에 본다.

## 3. 공정 비교의 핵심 = 모델별 prefix 규약

임베딩 모델은 같은 텍스트라도 앞에 붙이는 지시 prefix가 다르고, 이걸 안 맞추면 모델 잘못이 아니라 사용법 잘못으로 점수가 깎인다. 공정 비교의 전제는 "각 모델을 그 모델이 의도한 방식으로 호출하는 것"이다.

규약(EmbeddingService.prefix()의 모델별 분기):

| 모델 | 쿼리 prefix | 문서 prefix |
|---|---|---|
| nomic | `search_query: ` | `search_document: ` |
| granite-embedding | (없음) | (없음) |
| mxbai | `Represent this sentence for searching relevant passages: ` | (없음) |
| 그 외(기본) | (없음) | (없음) |

nomic은 비대칭 prefix가 필수다(쿼리와 문서를 다른 토큰으로 구분). granite는 prefix 없이 쓰도록 설계됐다. mxbai는 쿼리에만 지시문을 붙이고 문서는 그대로 넣는다.

설계: 흩어진 리터럴 prefix를 호출부마다 두면 모델을 바꿀 때마다 여러 파일을 고쳐야 하고 한 곳을 빠뜨리면 조용히 틀린다. 그래서 EmbeddingService에 `embedQuery()`/`embedDocument()` 두 진입점을 두고, prefix 분기를 `prefix()` 한 곳에 중앙화했다. 호출부(RagService.embedQuery, IngestionService.embedDocument, SemanticChunker.embedDocument)는 prefix 리터럴을 모른다. 모델을 바꿔도 호출부는 안 바뀌고, prefix 규약은 prefix() 한 메서드만 손대면 된다.

## 4. 측정 방법 = 환경변수 swap, yaml 아님

application.yaml의 임베더는 `embed-model: ${OLLAMA_EMBED_MODEL:nomic-embed-text}`이다. yaml을 고치지 않고 `OLLAMA_EMBED_MODEL` 환경변수로 모델만 갈아끼워 측정한다.

왜 yaml을 안 고치나:

- 실험은 일시적이다. 측정이 끝나면 후보 3개는 버린다. 영구 설정 파일을 임시 실험으로 더럽히지 않는다.
- 재현·기록이 깔끔하다. 측정 모델명이 실행 명령 자체에 박혀 있어 "어떤 모델로 잰 숫자인가"가 명령 히스토리에 남는다. yaml을 매번 고치면 어느 시점에 무엇이었는지 추적이 흐려진다.
- 끝나면 승자만 yaml 기본값에 반영한다.

측정 1사이클(모델마다 반복):

```bash
pkill -f spring-boot                                   # 이전 인스턴스 종료
rm -f data/articles.json data/articles.parquet data/.articles.parquet.crc   # 차원 혼입 방지 wipe
OLLAMA_EMBED_MODEL=<model> ./mvnw spring-boot:run       # 측정 모델로 기동
bash eval/ingest_corpus.sh                              # 동일 코퍼스 고정 재현
python3 eval/run_eval.py                                # 정답셋 채점
```

wipe가 중요하다. 차원이 다른 벡터가 한 인덱스에 섞이면(예: 768 인덱스에 1024 벡터) 검색이 깨진다. 모델을 바꿀 때마다 저장 파일을 지워 차원 혼입을 막는다.

## 5. 코퍼스 (eval/golden.json, 35케이스)

| namespace | 케이스 | 언어/포맷 |
|---|---|---|
| default | 9 | 영어 Wikipedia + invoice(OCR) |
| IBM-blueprint-for-agentic-opeation | 11 | 영어 PDF |
| IBM-ceo-study-2026 | 4 | 영어 PDF |
| kr-bcg | 3 | 한국어 HTML |
| kr-hackathon | 2 | 한국어 DOCX |
| kr-medical | 2 | 한국어+영어 PPTX |
| kr-hwp | 2 | 한국어 HWP |
| kr-hwpx | 2 | 한국어 HWPX |

코퍼스 포맷은 PDF/HTML/DOCX/PPTX/PNG에 더해 HWP/HWPX(한글)까지 포함한다(추출 경로 상세는 [INGESTION-FORMATS.md](INGESTION-FORMATS.md)).

한국어 11케이스(kr-*, namespace 5개)가 다국어 모델(granite-embedding:278m) vs 영어중심(nomic)을 가르는 변별 축이다. 영어 24케이스에서 두 모델이 비슷해도, 한국어에서 갈리면 다국어 가치가 측정으로 드러난다.

## 6. 측정 3축

품질, 속도, 저장량을 따로 잰다. 한 축만 보면 트레이드오프를 못 본다.

- 품질: run_eval.py의 recall@k. 카테고리별(semantic/lexical/vocab-mismatch/discriminative-number 등)·언어별(영어/한국어)로 쪼개 본다. 평균 하나로 뭉개면 "영어는 같은데 한국어만 갈린다" 같은 신호가 사라진다.
- 속도: ingest 벽시계 시간. ingest_corpus.sh 전체를 도는 실측. 청크 수가 같으므로 모델 간 비교가 공정하다.
- 저장량: `data/articles.parquet` 파일 크기. 대략 차원 x 청크수 x 4바이트(float). 1024-dim은 768-dim 대비 +33%. 차원은 `/api/data/index/stats`의 `dimension` 필드로 확인한다(VectorIndex가 임베딩 실제 길이로 보고).

## 7. 결과 표 (채울 자리)

| 모델 | 차원 | recall(none/mmr) | 영어 | 한국어 | ingest(s) | parquet | 한 줄 평 |
|---|---|---|---|---|---|---|---|
| granite-embedding:30m | 384 | 측정 예정 | 측정 예정 | 측정 예정 | 측정 예정 | 측정 예정 | 측정 예정 |
| nomic-embed-text | 768 | baseline | 영어+CEO 23/24 (한국어 추가 전 측정값) | 재측정 필요 | 측정 예정 | 측정 예정 | 한국어/풀세트 재측정 필요 |
| granite-embedding:278m | 768 | 측정 예정 | 측정 예정 | 측정 예정 | 측정 예정 | 측정 예정 | 측정 예정 |
| mxbai-embed-large | 1024 | 측정 예정 | 측정 예정 | 측정 예정 | 측정 예정 | 측정 예정 | 측정 예정 |

nomic의 영어+CEO 23/24는 한국어 케이스를 코퍼스에 넣기 전의 측정값이다. 풀세트(35케이스, 한국어 포함)로 다시 잰 뒤 채운다.

## 8. 선택 가이드 (가설 — 측정 후 확정)

지금은 가설이다. 측정값이 나오면 확정·수정한다.

| 차원/모델 | 가설 | 근거(가설) |
|---|---|---|
| 384 (granite 30m) | 속도·엣지·대량 | 작고 빠름, recall 약간 손해 감수 |
| 768 (nomic) | 균형 기본 | 품질·비용 sweet spot |
| 1024 (mxbai) | 품질↑ 저장↑ | 표현력↑, 단 저장 +33%·연산↑ |
| 다국어 (granite 278m) | 한국어 등 비영어 | 다국어 학습, kr-* 케이스에서 우위 기대 |

교차 비교는 [DECISIONS.md](DECISIONS.md) 4절.

## 9. 제약: pgvector는 차원 고정

pgvector 컬럼은 `vector(768)`로 차원이 고정돼 있어 384/1024를 같은 스키마에 못 넣는다. 따라서 차원 비교(384 vs 768 vs 1024)는 인메모리 VectorIndex(차원 무관)에서만 한다. 768 두 모델(nomic, granite-278m)만 나중에 pgvector로 옮겨 운영 환경에서 재확인한다. 인메모리는 재시작 시 재인덱싱이 필요하지만 차원 실험에는 그 유연성이 더 중요하다(DECISIONS.md 5절).
