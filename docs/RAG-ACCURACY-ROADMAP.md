# RAG 정확도 로드맵 — 매뉴얼 KB 확장 대응

> **한 줄 요약**: 461권 매뉴얼 KB가 커지면서 의미 검색만으론 차종이 섞이는 문제. 처방은 **메타 1차 필터(1)** → **하이브리드 검색 보강(2)** → **크로스인코더 리랭커(3)**. 각 단계의 효과는 **RAGAS-lite로 같은 잣대로 측정**해 단계별 delta를 적재한다.

## 배경 — 왜 이 작업을 하는가

이 프로젝트(`miniwatson_Veichle`)는 현대차 A/S 도메인 RAG 어시스턴트다. 매뉴얼은 운전자용 오너스 매뉴얼 461권(연식·차종·구동계별 분리):

- 출처: 현대 공식 포털 + Internet Archive
- 보관: `data/vehicle/manuals/` (PDF), 청크로 임베딩 후 `data/articles/` (Parquet 파티션) 적재
- 검색: namespace=`vehicle` 단일 KB

문제: KB가 커질수록 "투싼 하이브리드 정기점검" 같은 질의에 코나·아반떼 등 무관 차종 청크가 후보로 섞여 답변 품질이 저하. 의미 임베딩만으론 차종을 정확히 분리하지 못한다.

처방의 방향성: 검색 정확도는 단일 기법으로 해결되지 않는다. **메타 필터(coarse) → 의미+키워드(medium) → 리랭커(fine)**로 점진적으로 좁히는 것이 표준 패턴.

## 현 상황 (2026-06-24 기준)

| 단계 | 무엇 | 상태 | 문서 |
|---|---|---|---|
| 0 — 측정 레이어 | RAGAS-lite 4메트릭(Ollama judge) | ✅ 하네스 완료 | [`RAG-EVAL-RAGAS.md`](RAG-EVAL-RAGAS.md) |
| 1 — 메타 1차 필터 | 차종·연식·언어·구동계 컬럼 + 파일명 표준화 + 필터 API | ✅ 코드 통합 (앱 재기동 필요) | [`RAG-MANUAL-METADATA.md`](RAG-MANUAL-METADATA.md) |
| 2 — 하이브리드 검색 보강 | BM25 한글 토크나이저 fix(어절 + 2-gram) | ✅ 코드 통합 (앱 재기동 필요) | [`RAG-HYBRID-RETRIEVAL.md`](RAG-HYBRID-RETRIEVAL.md) |
| 3 — 크로스인코더 리랭커 | bge-reranker-base 등 (top-K 정렬 정확도) | ⚪ 예정 — 2단계 측정 후 판단 | — |
| (별도) 섹션 메타 | 매뉴얼 헤딩/TOC 파싱 → 안전/정비/제원 청크 라벨 | ⚪ 예정 | — |

현재 KB 적재 상태(`GET /api/data/documents?namespace=vehicle`): **2 매뉴얼 / 378 청크**(`hyundai_2025_ioniq5_NE1_owners_KR.pdf #366`, `hyundai_2025_ioniq5_n_NE1N_owners_KR.pdf #12`). 두 매뉴얼 모두 이미 신규 파일명 규칙(`hyundai_<year>_<model>[_pt]_<code>_owners_<region>.pdf`)으로 적재됨. 나머지 매뉴얼은 신규 코드(파일명 표준화·메타 파서·백필 잡)가 살아 있는 상태로 재인제스트하면 메타가 자동 채워진다.

## 단계 ↔ RAGAS 메트릭 매핑(가설)

| 단계 | 주요 끌어올릴 메트릭 | 이유 |
|---|---|---|
| 1단계(메타 필터) | **context_precision ↑** | 무관 차종 청크가 후보에서 빠짐. recall은 메타 매칭 청크는 남으므로 의미 있게 안 변함 |
| 2단계(BM25 한글 fix) | **context_recall ↑** | 벡터가 놓친 키워드 일치(부품명·DTC) 청크를 BM25가 잡음 |
| 3단계(리랭커) | **faithfulness, answer_relevance ↑** | top-K 안의 순서가 정확해 LLM이 정답 컨텍스트를 인용 → 환각·회피 ↓ |

검증은 매 단계 적용 전/후 `python3 eval/run_ragas.py` 실행해 delta 표 작성. [`RAG-EVAL-RAGAS.md`](RAG-EVAL-RAGAS.md) §"인사이트" 섹션에 누적.

## 이번 PR의 코드 변경 인덱스

- **파일명 표준화** (`scripts/rename_manuals.py`) — 459 PDF → `hyundai_<year>_<model>[_pt]_<CODE>_owners_<REGION>.pdf` (89/90 코드 매핑 확정). DB title 마이그레이션 SQL도 동시 산출.
- **메타 6필드** — `Article.java`(`carCode, carModel, powertrain, year, lang, region`), `article.avsc`(nullable union, 구 Parquet 호환).
- **파일명 파서** — `ManualMeta.java`(신규). 표준 파일명을 메타로 분해.
- **적재 경로 주입** — `IngestionService.ingestText()`에서 청크별 `ManualMeta.apply()`.
- **Parquet 입출력** — `ArticleParquetStore`의 read/write에 6필드, 구 schema NPE-free.
- **메타 1차 필터** — `RagService.ask(..., car, year, lang, powertrain)`. 후보 받은 뒤 stream filter, title 필터와 직교.
- **REST 노출** — `AskRequest`에 4필드, `RagController`에서 전달.
- **백필 잡** — `BackfillManualMetaRunner` (`CommandLineRunner`, idempotent, 기동 시 1회. `backfill.manual-meta.enabled` 토글).
- **BM25 한글 토큰화** — `KeywordIndex.tokenize()`: 한글/영숫자만 토큰 인정, 한글 어절은 char-2gram도 함께 색인. `hydrate()`에 어휘 통계 로그 추가(회귀 즉시 감지).
- **RAGAS-lite 하네스** — `eval/run_ragas.py`. 4메트릭(faithfulness/answer_relevance/context_precision/context_recall), Ollama judge, 메타 필터 옵션 노출.

## 검증 방법

1. **코드 통합** — `./mvnw -q -DskipTests compile` ✓
2. **앱 재기동 후 백필 잡 로그 확인** — `[backfill] manual-meta done — scanned=378 updated=N skipped(already)=M …`
3. **BM25 통계 회귀 감지** — `[KeywordIndex] ns='vehicle' docs=378 vocab=… avgTokens=…` 로그에서 한글 토큰화 확인(이전 회귀 시 vocab 크기 급감으로 즉시 보임).
4. **RAGAS 베이스라인** — `python3 eval/run_ragas.py` 4메트릭 평균 측정.
5. **단계별 비교** — 메타 필터 on/off(`--car ioniq5` vs none), hybrid on/off(EVAL 오버라이드)로 2×2 grid 비교 — [`RAG-EVAL-RAGAS.md`](RAG-EVAL-RAGAS.md)에 결과 적재.

## T0 베이스라인 측정 결과 (2026-06-24)

`eval/ragas_T0_baseline.txt` 보존. 핵심:

| 메트릭 | 값 | 신호 |
|---|---|---|
| context_recall | **1.00** | KB 적재 범위 안에서는 정답 정보가 전부 잡힘 — 더 큰 KB가 들어와야 2단계(BM25) 효과 비교 가능 |
| context_precision | 0.60 | 5 중 2케이스 0.00 — 둘 다 **현 KB 범위 밖**(P0420은 가솔린 엔진 DTC, ioniq5는 EV) |
| faithfulness | 0.52 | 답변 절반이 컨텍스트 미지지 — **환각 시그널** (KB 범위 밖 질문에서 모델 사전지식 사용) |
| answer_relevance | 0.30 | 낮음 — judge 까다로움 가능. 다른 judge(qwen3:8b)로 sanity check 권장 |

전체 해석·케이스별 표·다음 결정은 [`RAG-EVAL-RAGAS.md` §8](RAG-EVAL-RAGAS.md#8-인사이트-측정-결과-누적).

## 다음 결정점

T0 결과가 드러낸 현실:

1. **KB가 ioniq5 2권뿐이라 1·2단계 효과 측정의 의미가 작다.** 메타 필터(1)는 후보가 ioniq5만이라 필터링할 게 없고, BM25 한글 fix(2)는 recall이 이미 천장(1.00)이라 회복할 누락이 없음.
2. **공정한 측정을 위한 두 길**:
   - (A) **다른 차종 매뉴얼 추가 적재** — 가장 정직. `python scripts/fetch_ingest_kr_manuals.py --models "투싼,싼타페,그랜저"` 정도로 4~5권 추가하면 메타 필터·하이브리드 모두 효과 측정 가능. 임베딩 부하 ~수시간.
   - (B) **골든셋을 현재 KB(ioniq5) 범위 안으로 좁힘** — 빠름. P0420 같은 가솔린 케이스 빼고 EV·아이오닉5 전용 질문으로 재작성하면 즉시 측정 가능.
3. **환각(faithfulness)은 1·2·3단계가 직접 잡지 않음** — 별도 작업(프롬프트에 "컨텍스트에 없으면 모른다" 강화 또는 컨텍스트 양 증가).
4. 위 결정 후 **3단계 리랭커 도입 여부**는 T2/T3 비교 결과로 판단.

또한: 매뉴얼 KB가 더 커지면 **섹션 메타**(헤딩/TOC 파싱) 추가 — 안전/정비/제원/경고등 단위 필터로 한 차원 더 좁힘.

## 맥락 회복용 짧은 Q&A

**Q. 왜 메타 필터를 먼저?**
A. 매뉴얼 KB의 노이즈는 본질적으로 "무관 차종/연식"이라 임베딩 튜닝보다 메타 한 줄로 가장 큰 효과. 의미 검색은 이 위에서 더 잘 작동.

**Q. 왜 한글 토크나이저가 결함이었나?**
A. 기존 `[^a-z0-9]+` 정규식이 한글 전부를 split해 BM25 점수가 0 → 하이브리드가 사실상 벡터 단독. 가장 작은 변경(정규식 + 2-gram)으로 가장 큰 효과.

**Q. 왜 ragas 라이브러리 안 쓰고 직접?**
A. 본 프로젝트는 로컬 Ollama 단일 의존이라 LangChain/OpenAI 결합도가 큰 ragas는 부적합. 메트릭 4개는 LLM judge 프롬프트로 등가 구현 가능. 단계별 delta가 중요하지 절대값은 아니라 자체 judge로도 충분.

**Q. 왜 측정을 단계 사이에 두나?**
A. 측정 없이 단계를 쌓으면 "좋아졌다"를 증명 못 한다. 매 단계 효과를 같은 잣대로 비교해야 다음 단계 진입 여부(특히 비용 큰 3단계)를 합리적으로 판단.
