# Decision Guide — 언제 무엇을 쓰나

파이프라인의 교체 가능한 전략(chunking, reranking, hybrid, embedding, vector store, DB)을 "언제 뭘 고르나" 기준으로 한곳에 모은 인덱스. 각 항목의 근거와 측정은 해당 doc에 있고, 여기선 결론만 본다.

관통하는 규칙 하나: 후처리(rerank/hybrid)는 1차 검색이 약하거나 코퍼스가 크고 노이즈 많을 때만 이득이다. 1차가 강하면 한계이득이 0이거나 음수다. 아래 선택 대부분이 이 규칙의 적용이다.

---

## 1. Chunking 전략

문서를 검색 단위로 쪼개는 방식. 설정: `chunking.strategy`.

| 상황 | 추천 | 이유 |
|---|---|---|
| 기본/대부분 | recursive | 비용 0(문자열 연산만), 문단/문장 경계 보존, 즉시 |
| 의미 경계가 중요 + 인덱싱 시간 여유 | semantic | 주제 단위로 묶어 경계 품질 최고. 단 문장마다 임베딩 호출 -> 850자 문서에 3.45초, 큰 문서는 분 단위 |
| 속도 극단 우선 / 구조 없는 텍스트 | fixed | 가장 빠름. 단어/문장 중간이 잘려 경계 품질 낮음(baseline) |

실측: 같은 850자 문서에서 fixed 8청크(중복과 단어잘림), recursive 6청크(경계보존), semantic 5청크(의미묶음, 3.45s). 기본은 recursive.

추가 고려:
- `chunking.max-size` — 작은 문서를 비교할 땐 250, 실사용은 800~1000.
- 긴 단일주제 문서는 청크가 많아도(예: 101청크) 검색이 잘 되지만, "문서 전체가 답"인 질문은 retrieval로 못 잡는다(아래 5절 참고).
- 청킹 앞단의 포맷 추출(PDF/DOCX/HWP/이미지 등 -> 텍스트)은 [INGESTION-FORMATS.md](INGESTION-FORMATS.md).

---

## 2. Reranking 전략

1차 후보(top-N=20)를 재정렬해 최종 top-K(2)를 고른다. 설정: `rerank.strategy`.

| 상황 | 추천 | 이유 |
|---|---|---|
| 1차 검색이 이미 강함 (작은/깨끗한 코퍼스) | none 또는 mmr | 측정상 recall 100%. 후처리 이득 0 |
| 후보에 거의 같은 청크가 여럿 | mmr | 다양성 패널티로 서로 다른 측면을 뽑음 |
| 어휘 불일치/미묘한 의도 구분 + 약한 1차 | llm | 후보를 LLM에 listwise로 줘 의도까지 재정렬. 단 LLM 호출 1회 추가 |
| 대규모, 고정밀 필요 + Linux/GPU | cross | DJL cross-encoder 전용 모델. 최고 정밀도. 인텔 맥에선 네이티브 부재로 폴백 |

실측(20케이스): none/mmr recall 100%, **llm rerank는 85%로 오히려 깎임**(pods, negation 등 정답 청크를 top-K 밖으로 밀어냄). 이 코퍼스의 best는 none/mmr.

교훈: **rerank를 무조건 붙이지 않는다.** 1차가 강하면 llm rerank가 정답을 밀어낼 수 있다. 측정으로 결정한다.

---

## 3. Hybrid (벡터 + BM25)

1차 검색을 벡터(의미)만 vs 벡터+BM25(어휘) RRF 융합. 설정: `retrieval.hybrid.enabled`.

| 상황 | 추천 | 이유 |
|---|---|---|
| 정확 토큰 질의 (ID, 코드, 제품명) | hybrid on | 임베딩은 "INV-2026-0042"를 의미로 못 잡음. BM25가 정확 매칭 |
| 의미 질의 위주 + 작은 코퍼스 | 차이 작음 | top-N이 코퍼스를 거의 덮어 벡터만으로 충분 |
| 크고 노이즈 많은 코퍼스 + 희귀 어휘 | hybrid on | 어휘 신호가 변별력을 줌 |

한계: 질의어가 코퍼스 전체에 흔하면(저 IDF) BM25도 약하다. "5.4x" 같은 흔한 숫자나, 문서 전체가 한 주제인 경우는 hybrid로도 못 잡는다.

---

## 4. Embedding 모델 / 차원

측정 완료 — 4개 모델을 동일 코퍼스(35케이스)로 비교. **기본값 = granite-embedding:278m**(한+영 혼재 코퍼스서 recall 97%, 한국어 11/11). 상세 내용과 결과표는 [EMBEDDINGS.md](EMBEDDINGS.md) 7절.

| 상황 | 후보 | 측정 결과 |
|---|---|---|
| 한국어+영어 혼재 (기본) | granite-embedding:278m (768, 다국어) | recall 97%, 한국어 만점. **승자** |
| 영어 중심 균형 | nomic (768) | recall 94%, 한국어 1개 놓침 |
| 속도, 대량, 엣지 | granite-embedding:30m (384) | recall 89%, 가장 작고 빠름 |
| (비권장) | mxbai (1024) | recall 94% — 최대 차원인데 nomic과 동점. 비용만 큼 |

핵심: **차원이 아니라 다국어 학습이 한국어 recall을 갈랐다.** mxbai(1024)가 granite-278m(768)에 짐. 영어 24케이스는 4종 거의 동점, 변별은 전적으로 한국어에서 발생.

주의: 임베더마다 prefix 규약이 다르다(nomic은 search_query/document 필수, granite는 불필요 — 그래서 현재 기본값 granite-278m에선 prefix가 비어 있다). 공정 비교하려면 모델별 prefix를 맞춰야 한다. 분기는 EmbeddingService.prefixFor() 한 곳.

---

## 5. Vector Store (인메모리 vs pgvector)

벡터 검색 저장소. VectorStore 인터페이스로 추상화. 두 구현체를 `vector.store` 설정으로 스위치(상호 배타 @ConditionalOnProperty) — 호출부 무변경. 상세는 [PGVECTOR.md](PGVECTOR.md).

| 상황 | 추천 | 이유 |
|---|---|---|
| 개발/소량/차원 실험 | InMemory (VectorIndex, LSH/brute-force) | 차원 무관, 즉시. 단 재시작 시 재인덱싱 |
| 영속성, 대규모, 운영 | pgvector (PgVectorStore, HNSW) | 디스크 영속, 재시작 후 재인덱싱 불필요, 수십만+ 확장. 단 차원 고정(vector(768)) |

LSH vs brute-force: 코퍼스가 작으면(수백~수천) brute-force가 항상 정확하고 충분히 빠르다. LSH는 수만+에서 속도를 위해 정확도(recall)를 희생하는 ANN.

차원 고정이 이관 경로를 정한다: pgvector는 vector(768)라 768 임베더(granite-278m/nomic)만 적재된다. 그래서 임베딩 4종 비교(384/768/1024)는 인메모리에서 하고, 승자(granite-278m, 768)만 pgvector로 운영 검증한다 — 비교의 유연성은 인메모리, 운영 영속성은 pgvector로 역할 분담.

---

## 6. 저장 티어 / DB

| 데이터 | 저장 | 이유 |
|---|---|---|
| audit log, document catalog | H2(dev/demo) / Postgres(prod) — OLTP | 행 단위 잦은 쓰기와 단건 조회. 트랜잭션 |
| Article + embedding (cold) | Parquet — OLAP/lakehouse | 대량 벡터 압축과 스캔. 열 지향 |

프로필: dev=H2 in-memory(빠른 개발), demo=H2 file(영속), prod=Postgres+pgvector. 규모가 커지면 audit는 웨어하우스로 ETL(OLTP/OLAP 분리, DATA-MODEL.md 12절).

---

## 7. Chat LLM (생성 모델)

| 상황 | 추천 | 이유 |
|---|---|---|
| 기본/데모 | ibm/granite4 (2.1GB) | 가볍고 빠름(약 4.9s), IBM 내러티브 |
| 품질 더 필요 | gemma4 (9.6GB) | 느림(약 19s)이고 메모리 큼. 데모엔 과함 |
| 큰 모델(qwen 23GB 등) | 비권장(로컬) | 로컬 추론은 모델 크기가 곧 비용. GPU/서버 전제 |

로컬 추론 교훈: 모델 크기가 곧 운영 비용. 작은 모델이 데모엔 충분하고, 큰 모델은 GPU/vLLM 서빙이 전제다.

> 갱신(자동 eval, [RESULTS.md](RESULTS.md) §2.2): RAG 동일·생성 모델만 바꿔 n=3 비교 → **qwen3:8b**(누수 0·점수 0.97·9.0s)가 1등, exaone3.5(만점·한국어 네이티브·느림)가 2등. **기본 모델을 `qwen3:8b`로 교체**(데이터 기반). granite4는 멀티프로바이더로 선택 가능하게 유지(IBM 내러티브). 1.5B FT는 영어 출력·환각으로 최하 → "작은 베이스+얇은 영문 데이터 FT"의 실패 교훈.

### 7.5 학습 예산 vs 서빙 예산 — 왜 7B인가, 왜 더 안 키웠나

"Colab을 쓰면 더 큰 모델도 되지 않나?"에 대한 답. **학습 환경과 서빙 환경은 별개의 예산**이다.

- **학습(Colab T4 16GB)**: QLoRA(4bit base + LoRA)라 **7B 여유, 13B 빡빡**. 34B/70B는 A100(Pro/유료)·멀티GPU 필요. → Colab은 *학습* 상한을 올린다.
- **서빙(로컬 M2, Ollama)**: 모델은 **맥 RAM**에 올라가야 한다. Colab은 12h면 끊기는 휘발성이라 서버가 아니다(어댑터/GGUF만 받아옴). → 서빙 상한은 *Colab과 무관하게* 맥 RAM이 정한다.

| 모델 | 학습(Colab) | 서빙 Q4 | 필요 맥 RAM |
|---|---|---|---|
| 7B (현재) | T4 여유 | ~4.7GB | 8GB+ |
| 13B | T4 빡빡 / Pro | ~8GB | 16GB+ |
| 34B | A100(Pro) | ~20GB | 32GB+ |
| 70B | A100 다중 | ~40GB | 64GB+ |

> 갱신(2026): "vLLM은 NVIDIA CUDA 전제"라는 통념은 **부분 구식**이 됐다 — **vLLM-Metal**(MLX 백엔드)이 Apple Silicon에서 vLLM(PagedAttention·KV cache·OpenAI API)을 돌린다. 즉 *서빙 엔진* 선택지는 넓어졌고, 상한은 여전히 맥 RAM이 정한다. 서빙 스택 사다리·로드맵은 [SERVING.md](SERVING.md).

결정: **7B QLoRA**가 (1.5B의 중국어 누수·환각을 잡으면서) **온디바이스 서빙**을 유지하는 최소점. 품질을 더 원하면 **13B가 가성비 스윗스팟**(학습 Colab 무료~Pro, 서빙 맥 RAM 16GB+). 34B+는 학습은 유료 GPU, 서빙은 클라우드 vLLM이 전제 → "온디바이스·데이터 주권" 전제가 깨지므로 PoC 범위 밖. 즉 모델 크기는 **두 예산(학습 VRAM·서빙 RAM)의 교집합**에서 고르며, Colab은 학습 칸만 넓힌다.

---

## 8. 멀티모달 (Vision vs OCR)

이미지에서 정보 추출 시 역할 분리.

| 추출 대상 | 사용 | 이유 |
|---|---|---|
| 정확한 숫자/텍스트 (송장 금액 등) | OCR (Tesseract) | 비전 모델은 숫자를 환각함($650, $5M 등 지어냄) |
| 레이아웃/문서 유형/맥락 | Vision (llava) | "표인가 차트인가, 무엇에 관한가" |

원칙: 둘을 합치되 충돌 시 **OCR을 권위로** 삼는다(프롬프트에 명시). 결합만으론 부족하고 어느 쪽이 authoritative인지 선언해야 한다. (MULTIMODAL.md)

---

## 9. 저장소 읽기 — load-once 캐시 vs 매 요청 재로드

콜드 티어(Parquet) 읽기를 어떻게 할지. 증상: 진단서를 느린 모델로 돌릴 때 `loadAll`이 요청마다(동시면 스레드별로) Parquet 357건을 디스크에서 재로드 → 모델 지연과 겹쳐 타임아웃(UI 500). 원인은 데이터 크기가 아니라 **접근 패턴**(매 요청 전체 재읽기)이었다.

| 방안 | 적합 | 트레이드오프 |
|---|---|---|
| **load-once 캐시 (채택)** | 지금: 소규모, 단일 인스턴스, 읽기 多 | 캐시 무효화 정확성 필요 |
| 인메모리를 단일 진실로 | 같은 규모, 더 깔끔 | 리팩터 범위 큼 |
| DuckDB로 Parquet 직접 쿼리(pushdown) | 중간 규모 | 구현 부담, 매 쿼리 디스크 접근 |
| pgvector 전환(이미 구현) | 수만+ | Postgres 인프라 전제 |

채택 이유: 변경 1파일, 저위험, 즉효. 357행(수 MB)은 메모리에 두는 게 정상 — 분산이나 외부 스토어는 이 규모에서 오버엔지니어링이고 "매 요청 재읽기"를 고치지도 못한다. 구현: 디스크 1회 읽고 캐시, `saveAll` 시 갱신, `synchronized`로 동시 단일 읽기, 호출부 변형 대비 방어적 복사.

캐시 무효화 안전성: 쓰기 경로가 `saveAll` 하나로 좁아 무효화 지점이 단일하다. 외부에서 파일 교체(reset_kb) 시엔 stale이지만, 그 런북이 "앱 끈 상태 실행"을 요구해 실무상 안전(재시작 시 재로드).

확장 사다리: **인메모리+캐시(현재) → pgvector(이미 보유, 서버측 ANN이라 전체 로드 불필요) → lakehouse(오브젝트스토리지+파티션 Parquet+DuckDB/Trino, 수백만+)**. 상세 [DATA-MODEL.md](DATA-MODEL.md), 디버깅 경위 [DEBUGGING.md](DEBUGGING.md) 4.11.

교훈: **"UI 500/느림 = 데이터가 큼"이 아니다.** 먼저 접근 패턴(매 요청 전체 재로드)을 의심하라 — 캐시 한 줄이 분산 인프라보다 먼저다. 스케일은 사다리로 올라가되, 현재 규모에 안 맞는 단계를 미리 깔지 않는다.

---

## 10. 프레임워크 vs 자체구현 (LangChain / LangGraph / LangSmith)

"왜 LangChain 안 썼어요?"는 거의 확실히 나오는 질문. 세 축으로 나눠 본다 — 셋 다 **의도적으로 자체구현**했고 이유가 같다.

| 축 | 대표 프레임워크 | 본 프로젝트 | 핵심 이유 |
|---|---|---|---|
| 오케스트레이션 | LangChain / **LangGraph**(상태그래프 에이전트) | Java `AgentService` (규칙기반 라우터 + LLM 폴백) | 흐름이 단순(라우팅→도구→종합), **결정성·투명성** > 프레임워크 편의 |
| 검색(retrieval) | LangChain Retriever / **GraphRAG** | 벡터+BM25 RRF (+ GraphRAG는 설계만) | 직접 제어, 측정으로 튜닝(§2~3). GraphRAG는 검색 *기법*이라 별개 축 |
| 관측/평가 | **LangSmith**(트레이싱·eval·모니터링 SaaS) | 거버넌스 감사로그 + Prometheus/Grafana + eval 하니스 | **데이터 주권** — 트레이스가 외부로 나가면 안 됨 |

관통하는 이유 셋:
1. **데이터 주권** — 플랫폼 명제가 현대 H-Chat식 "내부 게이트웨이". LangSmith(SaaS)는 트레이스를 외부 클라우드로 보내 명제와 충돌. 관측·평가를 내재화하는 게 일관됨.
2. **언어/스택 일치** — 서빙 본체가 Java/Spring. LangChain/LangGraph/LangSmith는 Python·JS 중심(LangChain4j는 미성숙). 자체구현이 본체와 자연스럽게 통합.
3. **결정성·거버넌스** — 규칙기반 라우터 + 결정적 SQL은 감사 가능하고 디버그 쉬움. 거버넌스 중심 플랫폼엔 프레임워크 추상화보다 제어력이 중요.

언제 프레임워크가 맞나(정직):
- **LangGraph** — 에이전트가 동적 다단계 계획·루프·재계획을 해야 할 때(지금은 단순해서 오버킬).
- **LangSmith** — 데이터 주권 제약이 없고 eval·관측을 빨리 깔아야 할 때. 트레이스 UI·회귀 워크플로가 자체구현보다 세련됨.
- **GraphRAG** — 멀티홉·연결 질의가 핵심일 때(설계는 [GRAPHRAG_VEHICLE.md](GRAPHRAG_VEHICLE.md)에 준비).

교훈: **"프레임워크를 몰라서 안 쓴 게 아니라, 트레이드오프를 알고 안 썼다."** 제약(주권·스택·규모)이 바뀌면 채택 조건도 명확하다 — 그 경계를 아는 게 엔지니어링 판단이다.

---

## 11. JD 갭 — 왜 안 했나 (오버스펙 vs 스케일칸 vs 스킬갭)

채용 JD의 고급 항목(Alignment Tuning, vLLM/sglang/TRT-LLM 서빙, prefix-aware routing, KV 최적화, GPU/노드 효율, 대규모 트래픽)이 이 프로젝트엔 없다. **"왜 없냐"가 면접 단골** — 무지성 누락이 아니라 규모/제약 기반의 의도적 판단임을 기록한다.

| 갭 | 왜 안 됨 | 분류 |
|---|---|---|
| vLLM/sglang/TRT-LLM 서빙 | M2엔 GPU 없음 → vLLM/TRT-LLM은 **CUDA 전용이라 물리적으로 불가**. 단일 사용자라 배칭할 트래픽도 없음 | **오버스펙 + 하드웨어 블록** |
| prefix-aware routing, KV 최적화, GPU/노드 효율 | 다중 동시요청 × GPU 클러스터에서만 이득. KV는 런타임(Ollama/vLLM)이 이미 처리. PoC엔 **최적화할 병목이 없음** | **오버스펙(스케일 칸)** |
| 대규모 트래픽 대응 | 1인 로컬 데모 — 트래픽 부재 | **오버스펙** |
| 멀티모달 *학습* | Vision+OCR은 응용만. 멀티모달 사전학습/파인튜닝은 데이터·연산 큼 | 오버스펙(연구 영역) |
| **Alignment Tuning (DPO/RLHF)** | SFT(LoRA) 다음 *단계*. 선호쌍 데이터 필요. 도메인 지식 반영엔 SFT가 우선 | **진짜 스킬 갭 — 0→1 데모 가치** |

판단 원칙:
- **없는 병목을 최적화하지 않는다.** vLLM의 연속배칭·PagedAttention·prefix-routing은 *트래픽 × GPU*가 전제다. 단일 M2 1인 데모엔 이득 0 → 만드는 게 오히려 오버엔지니어링. 추상화(`LlmClient→vLLM`)만 두고 실구현은 **스케일 칸(로드맵)** 으로 미루는 게 옳다.
- **하드웨어 제약을 인정한다.** vLLM/TRT-LLM은 CUDA 전용 — M2에선 클라우드 GPU를 빌려야 하고, 그건 "온디바이스 PoC" 명제와 충돌. 그래서 온디바이스는 Ollama+Q4, 클라우드는 vLLM provider 스왑으로 **역할 분리**.
- **Alignment만 성격이 다르다.** 오버스펙이 아니라 *아직 안 한 다음 단계*라, 작은 DPO 실험으로 0→1을 찍을 가치가 있다(§아래).

> 면접 한 줄: "vLLM·KV·prefix-routing은 GPU·트래픽이 전제라 단일 M2 PoC엔 오버스펙이라 의도적으로 제외하고 추상화·로드맵으로 뒀다. 없는 병목을 최적화하지 않는 게 옳다고 판단했다." → "다 때려박기"보다 강한 시그널.

---

## 관통 규칙

- 측정 없이 최적화 없다. chunking/rerank/hybrid/embedding은 정답셋으로 비교해 정한다.
- 후처리는 1차가 약할 때만 가치. 강하면 한계이득 0 또는 음수.
- 비용/품질 트레이드오프를 명시한다. 큰 차원, 큰 모델, semantic은 품질↑ 비용↑.
- 전략은 인터페이스로 추상화해 측정으로 갈아끼운다.
