# MiniWatson Vehicle

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](#license)

> **자동차 도메인 특화 LLM 플랫폼.** 정비 매뉴얼 RAG, 리콜/불만 text-to-SQL, 온디바이스 LoRA 파인튜닝, Agentic 진단과 부품 견적, 거버넌스까지 한 곳에서 다룬다.
>
> 스택: Spring Boot 4, Next.js, Ollama, DuckDB, MLX(LoRA), Qwen2.5 / IBM Granite.
>
> 현대차 NLP/LLM 직무 JD(도메인 특화 LLM 최적화, 경량화/추론, RAG와 Agent 고도화, 밸류체인 NLP, MLOps)에 매핑한 엔드투엔드 구현이다.

---

## 핵심 기능

- **매뉴얼 RAG** — 정비 매뉴얼을 근거로 한국어 답변과 출처를 준다. 다국어 임베딩으로 한국어 질문이 영어 매뉴얼을 교차 검색한다.
- **리콜/불만 text-to-SQL** — NHTSA 리콜/불만과 부품 CSV를 자연어로 질의(DuckDB)해 집계하고 차트로 본다. SQL 자기수정이 내장돼 있다.
- **온디바이스 LoRA 파인튜닝** — Qwen2.5-1.5B를 자동차 도메인으로 학습(MLX, GPU 없는 맥)한 뒤 GGUF Q4로 양자화해 Ollama로 서빙한다.
- **Agentic Search** — 질문을 받아 도구(RAG / 리콜SQL / 복합)를 고르고, 실행한 뒤 한국어로 종합하며 트레이스를 남긴다.
- **차종 종합 진단서** — 리콜, 불만, 매뉴얼을 한 리포트로 종합한다(집계는 결정적 SQL, 서술은 LLM).
- **이미지 진단에서 부품까지** — 차량 사진을 Vision과 OCR로 읽어 매뉴얼 기반 진단을 하고 필요 부품과 샘플 견적을 낸다.
- **거버넌스** — 모든 LLM 호출을 감사 로그에 남기고 PII를 마스킹하며, 멀티프로바이더로 현대 H-Chat 게이트웨이와 정합한다.

> 데모 화면과 정량 결과는 [docs/RESULTS.md](docs/RESULTS.md), 설계 근거는 [docs/VEHICLE_ARCHITECTURE.md](docs/VEHICLE_ARCHITECTURE.md), 전체 문서 색인은 [docs/README.md](docs/README.md)를 참고.

---

## 프로젝트 성격 — PoC + 확장 로드맵

이 프로젝트는 **PoC(개념 증명)** 다. 증명하려는 가설은 "현대차 도메인 LLM 스택(도메인 LoRA, 경량화/온디바이스, RAG, Agent, 거버넌스)을 **GPU 없는 단일 맥(M2)** 에서 작게 재현할 수 있는가"다. 그래서 샘플 데이터, 1.5B 베이스, 인메모리 저장 같은 선택은 **규모가 아니라 가능성 검증**에 맞춘 의도적 결정이다.

각 컴포넌트는 "어디까지, 어떻게 키울지"를 **확장 사다리**로 문서화했다 — 지금 안 만드는 것도 의도다(규모에 안 맞는 단계는 오버엔지니어링).

| 영역 | 지금 (PoC) | 다음 | 스케일 |
|---|---|---|---|
| 모델 | 1.5B LoRA(MLX) Q4 | 7B QLoRA(Colab) Q4 | vLLM 서빙 |
| 벡터 저장 | 인메모리 + load-once 캐시 | pgvector(HNSW, 이미 구현) | 오브젝트스토리지 + 파티션 Parquet 레이크하우스 |
| 진단서 서술 | 작업별 모델 라우팅 | 7B FT(진단서 예시 학습) | — |
| 배포 | 로컬 | docker-compose | 클라우드(provider 스왑) |

근거: 모델 [docs/RESULTS.md](docs/RESULTS.md), 저장소 [docs/DATA-MODEL.md](docs/DATA-MODEL.md)와 [docs/DECISIONS.md](docs/DECISIONS.md), 디버깅 [docs/DEBUGGING.md](docs/DEBUGGING.md).

---

## 프로젝트 구조

3개 계층(서빙, 프론트, ML)이 HTTP와 모델파일로만 연결된 모노레포다.

```
miniwatson_Veichle/
├── src/, pom.xml, Dockerfile     # 백엔드 — Spring Boot 4 / Java 21 (RAG, Agent, 거버넌스, 서빙)
├── frontend/                     # 프론트 — Next.js (RAG, Agent, 진단서, SQL, 거버넌스 탭)
├── ml/                           # ML 사이드카 — Python (데이터수집, LoRA, 양자화, 벤치)
│   ├── data/      수집과 데이터셋 스크립트   ├── finetune/  LoRA 학습과 평가
│   └── optimize/  양자화와 벤치마크          └── RUNBOOK.md 실행 가이드
├── data/vehicle/                 # 도메인 데이터 (샘플 CSV만 커밋, 매뉴얼 PDF는 로컬)
├── docs/                         # 설계 문서 (아키텍처, LoRA, Agent, GraphRAG, 디자인, 결과)
├── reference/graphrag/           # GraphRAG 레퍼런스 (미통합, 고도화 설계 근거)
└── eval/, sample/, monitoring/   # 평가 하니스, 샘플, Prometheus/Grafana
```

> 백엔드를 `backend/`로 옮기지 않고 루트에 둔 건 의도적이다. 기존 Maven, Docker, CI 경로를 그대로 유지해 리스크를 줄였다. 분리는 디렉터리(`frontend/`, `ml/`)와 HTTP 경계로 이미 달성했다.

---

## 아키텍처

자동차 밸류체인 NLP을 서빙(Java)과 모델 제작(Python)으로 나누고, 완성한 모델을 `llm.provider`로 갈아끼운다. 이렇게 온디바이스와 클라우드 양쪽에 대응한다.

```
[ 사용자 ] ── Next.js (RAG, Agent, 진단서, SQL, 거버넌스 탭)
                  │  REST / JSON  (no CORS, /api 프록시)
                  ▼
[ 백엔드 ] Spring Boot 4 / Java 21
   ├─ Agent      질문 → 도구선택(RAG/SQL/복합) → 종합 + 트레이스, SQL 자기수정
   ├─ RAG        chunk → embed → 하이브리드(vector+BM25, RRF) → rerank → 한국어 생성
   ├─ Tabular    리콜/불만/부품 CSV → text-to-SQL (DuckDB)
   ├─ 멀티모달   Vision(llava) + OCR(Tesseract) → 이미지 진단
   ├─ 거버넌스   감사 로그, PII 마스킹, 멀티프로바이더(H-Chat 정합)
   └─ LlmClient 추상화 ── ollama | watsonx | vertex | bedrock | azure | vLLM
                  │  HTTP / Modelfile
                  ▼
[ ML 사이드카 (ml/) ] Python — 데이터수집 → LoRA(MLX) → GGUF Q4 양자화 → 벤치
                  → 완성 모델을 Ollama/vLLM로 서빙 → 백엔드가 호출
```

자세한 내용은 [docs/VEHICLE_ARCHITECTURE.md](docs/VEHICLE_ARCHITECTURE.md)에 있다.

---

## ML 파이프라인 (`ml/`)

GPU 없는 맥(M2)이라는 제약 때문에 작은 모델에 LoRA를 얹고 4bit로 양자화하는 온디바이스 컨셉을 택했다. 이 제약이 곧 JD가 말하는 "차량 온디바이스 추론 최적화" 스토리가 된다.

```
fetch_recalls.py / fetch_complaints.py / fetch_manuals.py   # 데이터 수집 (원본 보존)
        ↓
build_dataset.py            # 청크 → 한국어 instruction JSONL (선생모델 qwen2.5:7b, CJK 누수 필터)
        ↓
train_lora.py (MLX)         # Qwen2.5-1.5B LoRA 파인튜닝 (맥 GPU/Metal), early-stopping
        ↓
eval_adapter.py             # base vs FT 정성/정량 비교
        ↓
quantize_gguf.sh            # GGUF Q4_K_M 양자화 → Ollama 등록 (네이티브 safetensors import)
        ↓
benchmark.py                # TTFT, tok/s, 메모리 (Q4 vs Q8: 메모리 절반, 속도 약 2배)
```

| 데이터 | 소스 (공개, 재현 가능) | 사용처 |
|---|---|---|
| 정비 매뉴얼 | Internet Archive (공개 PDF) | 매뉴얼 RAG |
| 리콜/결함 | NHTSA API (키 불필요) | text-to-SQL, 진단서 |
| 불만(complaints) | NHTSA API | text-to-SQL, 진단서 |
| 성능·상태점검기록부 | 자동차관리법 별지 제82호 표준양식 (샘플) | 진단서 스키마 |

> 현대차 공홈(oms.hmc.co.kr)은 로그인과 ToS, JS 렌더 제약이 있어 공개되고 재현 가능한 소스로 대체했다. 매뉴얼 원문과 원본 JSON은 `data/vehicle/`(gitignore)에 두고 샘플 CSV만 커밋한다. 실행 단계는 [ml/RUNBOOK.md](ml/RUNBOOK.md), 소스와 라이선스는 [ml/data/sources.md](ml/data/sources.md)에 정리했다.

---

## 빠른 시작

### 사전 준비

```bash
# 1. Java 21 (Temurin/HotSpot)
java --version            # → openjdk 21+

# 2. Ollama + 모델
brew install ollama
ollama pull ibm/granite4:latest        # chat (default)
ollama pull granite-embedding:278m     # 768-dim 다국어 임베딩
ollama pull llava                      # vision (이미지 진단)

# 3. OCR (이미지 정확 추출)
brew install tesseract

# 4. Ollama 서버 (별도 터미널)
ollama serve
```

### 실행

```bash
git clone https://github.com/dea980/miniwatson_Veichle.git
cd miniwatson_Veichle

# 백엔드 (루트)
./mvnw spring-boot:run            # → http://localhost:8080

# 프론트 (frontend/, 별도 터미널)
cd frontend && npm install && npm run dev   # → http://localhost:3000
```

### 자동차 데모 흐름

1. **인제스트** — 정비 매뉴얼 PDF를 업로드한다 (namespace=`vehicle`).
2. **질문** — "P0420 코드가 뭐야?" → 매뉴얼 근거로 한국어 답변과 출처를 받는다.
3. **리콜 SQL** — "팰리세이드 리콜 몇 건이야?" → DuckDB가 집계한다.
4. **진단서** — 차종을 넣으면 리콜, 불만, 매뉴얼을 종합한 리포트를 PDF로 뽑는다.
5. **이미지 진단** — 차량 사진을 올리면 진단 결과와 필요 부품, 샘플 견적을 준다.

---

## 주요 API (자동차)

```bash
# Agentic Search — 도구 자동선택 + 트레이스
curl -X POST http://localhost:8080/api/agent/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "팰리세이드 안전벨트 관련 이슈 정리해줘"}'

# 차종 종합 진단서 (리콜+불만+매뉴얼) — 차종은 car
curl -X POST http://localhost:8080/api/agent/report \
  -H "Content-Type: application/json" -d '{"car": "PALISADE"}'

# 이미지 진단 → 한국어 진단 (image, namespace, model 파라미터)
curl -X POST http://localhost:8080/api/agent/diagnose-image \
  -F "image=@dashboard.jpg" -F "namespace=vehicle"

# 필요 부품 산정 (problem=증상, car=차종 / 부품 선택은 LLM, 금액 계산은 Java 결정적)
curl -X POST http://localhost:8080/api/agent/estimate \
  -H "Content-Type: application/json" -d '{"problem": "브레이크 소음", "car": "PALISADE"}'

# 리콜/불만 text-to-SQL — table 지정 필수 (먼저 /api/tabular/load로 등록)
curl -X POST http://localhost:8080/api/tabular/load \
  -H "Content-Type: application/json" \
  -d '{}' "http://localhost:8080/api/tabular/load?table=recalls&path=data/vehicle/recalls/hyundai_recalls_nhtsa.csv"
curl -X POST http://localhost:8080/api/tabular/ask \
  -H "Content-Type: application/json" -d '{"table": "recalls", "question": "연도별 리콜 건수"}'
```

> 자연어 질문만으로 도구·테이블을 자동 선택하게 하려면 `/api/agent/ask`를 쓰면 된다(리콜/불만 테이블 자동 로드 + 라우팅).

매뉴얼 RAG, 파일 업로드, 거버넌스 같은 플랫폼 공통 API는 [docs/API.md](docs/API.md)에 있다.

---

## 로드맵 (Vehicle 트랙)

- [x] **V1 데이터 수집** — NHTSA 리콜/불만 API(원본 JSON 보존)와 Internet Archive 매뉴얼 PDF (`ml/data/fetch_*.py`)
- [x] **V2 자동차 RAG** — `vehicle` 네임스페이스, 약어/DTC 시드 사전, 한국어 답변과 근거
- [x] **V3 리콜 text-to-SQL** — `/api/tabular` (DuckDB)
- [x] **V4 도메인 LoRA** — Qwen2.5-1.5B (MLX, early-stopping)와 base vs FT 평가
- [x] **V5 경량화** — GGUF Q4로 Ollama 서빙, 벤치(Q4 vs Q8: 메모리 절반, 속도 약 2배)
- [x] **V6 Agentic Search** — 도구선택(RAG/SQL/복합), 종합, 트레이스 (`/api/agent`)
- [x] **V7 UI** — Next.js 7탭과 브라우저 음성(STT/TTS)
- [x] **V8 멀티테이블** — NHTSA 불만 추가, 설정 레지스트리(`vehicle.tables`) 동적 선택
- [x] **V9 종합 진단서** — 리콜, 불만, 매뉴얼 종합 (`/api/agent/report`, 집계는 결정적 SQL)
- [x] **V10 이미지 진단에서 부품까지** — 사진(Vision+OCR)에서 매뉴얼 진단, 부품 명세와 샘플 견적
- [x] **V11 고도화** — text-to-SQL 자기수정, 작업별 모델 라우팅(SQL은 강한 모델, 답변은 FT)
- [x] **V12 온디바이스 STT** — 로컬 Whisper(faster-whisper) STT 서비스 (`ml/serve/whisper_stt.py`), 오프라인·프라이빗
- [ ] **V13 (옵션)** — 7B QLoRA(Colab) 학습, vLLM provider, 임베딩 파인튜닝, 로컬 TTS·웨이크워드, 라이브 배포, GraphRAG 통합

> GraphRAG는 지금 설계서만 있고 구현은 안 됐다. 실제 RAG는 벡터+BM25 하이브리드로 동작한다. 고도화 방향은 [docs/GRAPHRAG_VEHICLE.md](docs/GRAPHRAG_VEHICLE.md)에 정리했다.

---

## 기반 플랫폼 (상속)

이 프로젝트는 watsonx 스타일 RAG 플랫폼 [MiniWatson](https://github.com/dea980/miniwatson)(data, ai, governance) 위에 자동차 도메인을 얹은 확장이다. 아래 플랫폼 역량을 상속해 자동차 트랙이 곧장 활용한다.

- **검색** — 인메모리/pgvector 벡터 인덱스, 하이브리드(vector+BM25, RRF), 플러그형 리랭커(none/llm/mmr/cross)
- **데이터** — Apache Tika 멀티포맷 인제스트(한국어 HWP/HWPX 포함), 청킹(fixed/recursive/semantic), 티어드 저장(JSON에서 Parquet)
- **임베딩** — 4종 비교 승자 `granite-embedding:278m` (768-dim, recall 97%, 한국어 11/11)
- **거버넌스** — 감사 로그, PII 마스킹, provenance, 멀티테넌트(API key/JWT)
- **운영** — Docker, GitHub/GitLab CI 테스트 게이트, Actuator/Prometheus/Grafana

플랫폼 심화 문서(임베딩 비교, 청킹, 하이브리드, 리랭킹, pgvector, 보안, 운영 등)와 엔지니어링 학습 노트는 [docs/README.md](docs/README.md) 색인을 참고. 원본 플랫폼 README는 상위 레포 [dea980/miniwatson](https://github.com/dea980/miniwatson)에 보존돼 있다.

---

## License

MIT

## Author

**Daeyeop Kim** — [github.com/dea980](https://github.com/dea980), kdea989@gmail.com
