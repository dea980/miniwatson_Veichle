# Vehicle 아키텍처 & 설계 이유 (현재 상태)

> 데이터, AI, 거버넌스 레이어를 갖춘 기반 플랫폼(MiniWatson, watsonx 클론)을 **자동차 도메인 특화 LLM 플랫폼**으로
> 확장한 현재 구조와 각 결정의 이유를 정리한다. (작성 시점: Day 1 완료, LoRA 진행 중)

---

## 1. 큰 그림 — 2계층 분리

핵심 설계: **서빙/오케스트레이션(Java)과 모델 제작(Python)을 분리**하고,
완성된 모델을 추론 제공자(`llm.provider`)로 다시 꽂는다.

```
┌──────────────────── 본체: Spring Boot (Java 21) ────────────────────┐
│  AI Layer        RAG: chunk→embed→hybrid(vector+BM25,RRF)→rerank     │
│                  text-to-SQL(DuckDB), 멀티모달(Vision+OCR)            │
│  Data Layer      인제스트(PDF/HWP/Tika), 티어드(JSON→Parquet)         │
│                  멀티테넌트 네임스페이스, 카탈로그(H2)                │
│  Governance      감사로그, PII 마스킹, provenance(근거청크)           │
│                                                                      │
│  LlmClient 추상화  ── @ConditionalOnProperty("llm.provider") ──┐     │
│     ollama | watsonx | vertex | bedrock | azure | (vllm 예정)  │     │
└────────────────────────────────────────────────────────────────┼────┘
                                                                   │ HTTP
                          ┌────────────────────────────────────────┘
                          ▼
┌──────────────── 모델 제작: Python ML 사이드카 (ml/) ────────────────┐
│  data/      fetch_recalls.py(NHTSA), fetch_manuals.py, build_dataset  │
│  finetune/  train_lora.py (MLX LoRA), eval_adapter.py                 │
│  optimize/  quantize_gguf.sh (GGUF Q4), benchmark.py                  │
│  → 산출물: 파인튜닝·양자화된 모델 → Ollama 등록 → 본체가 호출         │
└──────────────────────────────────────────────────────────────────────┘
```

**왜 이렇게 나눴나**
- LoRA/양자화 생태계(transformers, peft, mlx, llama.cpp)는 전부 Python뿐 → ML은 Python.
- 서빙, 거버넌스, 멀티테넌시는 이미 Java(Spring)로 견고하게 구현됨 → 그대로 유지.
- 둘은 코드 import가 아니라 **모델 파일 + HTTP API**로만 연결 → MLOps의 깔끔한 경계.
- = JD의 "MLOps 연계 + 배포 가능한 모듈 설계 + Inference API"와 동일한 분리.

---

## 2. 추론 제공자 추상화 (확장의 축)

`LlmClient` / `RawLlmProvider` 인터페이스 + `@ConditionalOnProperty(name="llm.provider")`로
활성 제공자 1개만 빈으로 뜬다.

- 현재 기본: **Ollama** (`ibm/granite4:latest`), 임베딩 `granite-embedding:278m`(768차원).
- 파인튜닝·양자화 모델도 **GGUF로 만들어 Ollama에 등록** → `llm.provider=ollama` 그대로 사용.
- 고처리량이 필요하면 `VllmLlmClient`(OpenAI 호환) 추가 → `llm.provider=vllm` (예정).

**이유:** 모델을 바꿔도 컨트롤러/RAG/거버넌스 코드는 한 줄도 안 바뀐다. 온디바이스(Ollama)↔클라우드(vLLM) 전환이 설정 교체로 끝남.

---

## 3. 데이터 계층 — 자동차 도메인

두 종류의 데이터가 **두 경로**로 처리된다 (비정형↔정형 분리).

| 데이터 | 소스 | 수집 | 처리 경로 | 비고 |
|---|---|---|---|---|
| 리콜/결함 | **NHTSA API**(키 불필요) | `fetch_recalls.py` | text-to-SQL(`/api/tabular`, DuckDB) | 정형 → 집계 질의 |
| 오너스 매뉴얼(취급설명서) | **Internet Archive**(공개 PDF) | `fetch_manuals.py` | RAG(`/api/data/ingest-file`→`/api/rag`) | 비정형 → 서술 질의. 공장 정비 매뉴얼은 비공개·유료라 미포함 |

**왜 이 소스인가 (현대차 공홈 대신)**
- `oms.hmc.co.kr`(오너스매뉴얼 포털)은 **로그인 필요(딜러용)** → 접근 불가.
- 공홈 대량 크롤링은 ToS와 저작권 리스크 + JS 렌더로 불안정.
- → **공개돼 있고 재현 가능한** 소스로: NHTSA(공개 API), Internet Archive(공개 PDF), 공공데이터포털(리콜 OpenAPI, 사용자 키 발급 필요).
- 원문 저작물은 `data/vehicle/`(gitignore)에 로컬 인덱싱만, 출처는 `_provenance.csv`/`raw/*.json`에 기록.

**수집 파이프라인 특징**
- `fetch_recalls.py`: 모델×연식 순회 → **원본 JSON 보존**(`raw/`) + 평탄화 CSV. 맥 SSL 이슈 대비 `--insecure`/certifi 처리.
- `fetch_manuals.py`: 매니페스트(URL 목록) 기반 일괄 다운로드 + PDF 매직넘버 검증 + 출처기록.

---

## 4. 자동차 RAG 강화

- **네임스페이스 `vehicle`** 로 격리 인제스트 (기존 멀티테넌시 활용). 다른 도메인 데이터와 안 섞임.
- **약어/DTC 시드 사전**(`src/main/resources/vehicle/automotive-glossary.json`): ECU, TPMS, P0420 등.
  - `IngestionService`가 기동 시 로드(`loadSeedGlossary`) → 인제스트 시 문서 추출 글로서리와 병합 → `AcronymExpander.expand`가 청크에 정식명 주입.
  - **이유:** 매뉴얼엔 약어 정의가 없이 등장 → 임베딩이 안 붙는 구조적 miss를 시드로 보강. `AcronymExpander`(순수함수)는 안 건드리고 **데이터(JSON)만 주입** → 새 약어는 JSON만 수정.
- 검증: 질문→매뉴얼 근거(`sources`)→한국어 답변 동작 확인(accent 123청크).
  - 다국어 임베딩(granite-embedding 278m)으로 **한국어 질문 ↔ 영어 청크** 교차검색.
  - 답변 언어는 프롬프트 지정(`RagService`)으로 질문 언어를 따라가게 처리.

> ⚠️ 알려진 항목: 약어 시드의 `vehicle` 네임스페이스 가드는 선택 적용 상태(현재 vehicle 전용 KB라 영향 없음). 다도메인 복귀 시 가드 권장.

---

## 5. 모델 제작 파이프라인 (JD 헤드라인)

GPU 없는 맥(M2) → **온디바이스 컨셉**: 작은 모델 + LoRA + 4bit 양자화로 노트북 구동.

| 단계 | 스크립트 | 내용 |
|---|---|---|
| 데이터셋 | `build_dataset.py` | vehicle 청크 → Ollama로 한국어 instruction Q&A 합성(JSONL) |
| 파인튜닝 | `train_lora.py` | **Qwen2.5-1.5B-Instruct**(Apache-2.0)에 LoRA. MLX(맥 GPU) / HF(Colab) 듀얼 백엔드 |
| 평가 | `eval_adapter.py` | base vs 파인튜닝 비교(LLM-judge) |
| 경량화 | `quantize_gguf.sh` | 머지 → GGUF Q4_K_M → Ollama 등록 |
| 벤치마크 | `benchmark.py` | TTFT, tok/s, 지연, 메모리 → 양자화 전후 비교(기존 Prometheus 연계) |

**왜 작은 모델 + LoRA + 양자화**
- GPU 없음 → 7B CPU 학습은 비현실. 1.5B는 MLX로 맥에서 LoRA 학습 가능.
- 이 제약이 곧 JD의 "**온디바이스 대응 + 경량화·추론 최적화**" 스토리와 정합.
- LoRA = 전체 가중치 대신 저랭크 어댑터만 학습 → 작은 데이터와 메모리로 도메인 말투/형식 주입.

**RAG vs 파인튜닝 (왜 둘 다)**
- RAG = '지식'(최신 매뉴얼 내용)을 질의 시 주입. 파인튜닝 = '도메인 감각, 말투, 형식'을 가중치에 주입.
- 경쟁이 아니라 보완 → 함께 쓴다.

---

## 6. JD 매핑 & 진행 현황

| JD 항목 | 대응 | 상태 |
|---|---|---|
| 도메인 특화 LLM 최적화 | Vehicle 트랙 전체 | 🟡 진행 |
| 파인튜닝(PEFT/LoRA) | `train_lora.py`(MLX) | 🟡 데이터셋 생성 중 |
| 경량화·추론최적화(온디바이스) | `quantize_gguf.sh`+`benchmark.py` | ⚪ 스크립트 준비 |
| SOTA 조사와 오픈소스 벤치마킹 | 모델 비교 + benchmark | 🟡 일부 |
| RAG/Agent 고도화 | RAG 도메인화 / AgentController | 🟢 RAG / ⚪ Agent |
| 밸류체인 NLP(판매/제조/A·S) | A·S=매뉴얼RAG, 제조/품질=리콜SQL, 요약=기존 | 🟢 동작 |
| MLOps + Inference API | Spring API + Docker/CI + vLLM provider | 🟢 본체 / ⚪ vLLM |

**완료(Day 1):** 리콜 119건 수집(원본 JSON 보존), 매뉴얼 PDF 다운로드, accent 123청크 vehicle 인제스트, 도메인 RAG와 한국어 답변 검증, text-to-SQL 트랙 확보.

**다음:** LoRA 데이터셋 생성 → MLX 학습 → base vs FT 평가 → GGUF 양자화/벤치 → Agent(RAG+SQL 툴콜) + (옵션)vLLM 배포.

---

## 7. 기술 스택 요약

| 영역 | 선택 | 이유 |
|---|---|---|
| 본체 | Spring Boot 4 / Java 21 | 기존 견고한 서빙과 거버넌스 자산 |
| 추론(현재) | Ollama + granite4 | 자체호스팅, 키 불필요, provider 교체 가능 |
| 임베딩 | granite-embedding:278m(768d) | 다국어, 한국어 recall 우수 |
| 검색 | vector + BM25, RRF + rerank | lexical(코드/약어) + semantic 결합 |
| 정형 | DuckDB text-to-SQL | 리콜 집계 질의(zero-ETL) |
| Base LLM(파인튜닝) | Qwen2.5-1.5B-Instruct | Apache-2.0, 로컬 학습과 추론이 현실적 |
| 파인튜닝 | LoRA/QLoRA (MLX/peft) | 맥 GPU 학습, 경량 어댑터 |
| 경량화 | GGUF Q4_K_M | 온디바이스 Ollama 서빙 |
| 데이터 | NHTSA API, Internet Archive | 공개, 재현가능, 라이선스 명확 |
