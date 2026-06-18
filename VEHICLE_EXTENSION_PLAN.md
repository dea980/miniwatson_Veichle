# MiniWatson → Vehicle 확장 계획 (Automotive Domain LLM Platform)

> 목표: 완성된 watsonx 클론(MiniWatson, 로드맵 1–29)을 **자동차 도메인 특화 LLM 플랫폼**으로 확장.
> 현대자동차 NLP/LLM 직무 JD에 1:1 매핑되는 포트폴리오 트랙(로드맵 30+).

JD 매핑 한눈에:

| JD 요구사항 | 이 프로젝트의 대응 | Phase |
|---|---|---|
| 도메인 데이터 기반 LLM 파인튜닝 (PEFT, LoRA) | `ml/finetune/` LoRA/QLoRA 트랙 | P2 |
| 모델 경량화 및 추론 최적화 (온디바이스/클라우드) | GGUF 양자화(Ollama) + AWQ/vLLM, 벤치마크 | P3 |
| RAG/Agent 핵심기술 연구·고도화 | 기존 RAG 도메인화 + Agent 오케스트레이션 | P1, P4 |
| 자동차 밸류체인 NLP 서비스 (판매/제조/A·S) | 도메인 네임스페이스 + A/S 매뉴얼 검색 | P0, P1 |
| MLOps 연계, Inference API 설계·구현 | vLLM provider + OpenAI 호환 API, CI | P3, P4 |

---

## 설계 원칙 — 기존 아키텍처를 깨지 않는다

핵심: MiniWatson은 이미 **`LlmClient` / `RawLlmProvider` 추상화 + `@ConditionalOnProperty("llm.provider")`** 로
추론 제공자가 플러그인된다 (`ollama | watsonx | vertex | bedrock | azure`).

→ **Java 앱은 오케스트레이션·서빙·거버넌스 레이어로 유지**하고,
파인튜닝·경량화는 **Python ML 사이드카**로 분리한 뒤,
완성된 모델을 **새 `llm.provider=vllm` (OpenAI 호환)** 로 다시 꽂는다.

이게 JD의 "MLOps 연계 + 배포 가능한 모듈 설계 + Inference API"와 정확히 같은 경계선이다.

```
┌──────────────── 기존 (유지) ────────────────┐      ┌──────── 신규 (추가) ────────┐
│  Spring Boot (Java 21)                       │      │  Python ML 사이드카          │
│   • RAG: chunk→embed→hybrid→rerank           │      │   ml/data/    데이터 파이프  │
│   • Governance: audit/PII/provenance         │ ───▶ │   ml/finetune/ LoRA/QLoRA   │
│   • LlmClient 추상화 ◀──────────────┐        │      │   ml/optimize/ 양자화·벤치  │
│   • + AgentController (P4)          │        │      │   ml/serve/   vLLM (OpenAI) │
└────────────────────────────────────┼────────┘      └──────────────┬──────────────┘
                                      └── llm.provider=vllm ◀────────┘  추론 API
```

---

## Phase 0 — 데이터 수집 (현대차 도메인 코퍼스)  ⚠️ 먼저

> "현차 공홈 긁어오면 되지 않나?" → **권장하지 않음.** 공식 사이트 대량 크롤링은 ToS·저작권
> 리스크 + JS 렌더라 수집도 불안정. 포트폴리오는 **공식 배포 문서/공공데이터**로 떳떳하게 간다.

권장 소스 (합법·재현가능):

1. **오너스 매뉴얼 PDF** — 현대차가 공식 배포하는 차량 사용설명서(PDF).
   → A/S·매뉴얼 RAG의 핵심 코퍼스. **기존 PDF/HWP 인제스트 파이프라인이 이미 지원**.
2. **리콜 / 결함(DTC) 공공데이터** — 자동차리콜센터(car.go.kr), 공공데이터포털(data.go.kr),
   NHTSA(미국) 오픈데이터. 정형/반정형 → text-to-SQL + RAG 둘 다 활용.
3. **정비/보증 가이드, 스펙·카탈로그** — 공식 PDF/사양표(정형은 DuckDB text-to-SQL로).
4. **합성 Q&A (파인튜닝용)** — 위 문서에서 LLM으로 instruction 쌍 생성(distillation).

수집 규칙:
- `robots.txt` / 이용약관 준수, 개인용·연구용 소량 큐레이션만.
- 원문 저작권 표기 보존, 재배포 금지 자료는 로컬 인덱싱만(레포 커밋 금지 → `data/vehicle/`는 `.gitignore`).
- 가능하면 **수동 다운로드 + 스크립트 인제스트**(대량 자동 스크래핑 X).

산출물: `ml/data/sources.md`(출처·라이선스 표), `data/vehicle/` 코퍼스, 인제스트 스크립트.

---

## Phase 1 — RAG 도메인화 (A/S·매뉴얼)  [로드맵 30]

기존 RAG를 자동차 도메인으로 튜닝. **Java 측 소규모 변경 + 데이터/설정 중심.**

- [ ] `vehicle` 네임스페이스로 매뉴얼/리콜 코퍼스 인제스트 (멀티테넌시 그대로 활용)
- [ ] **자동차 약어/부품코드 사전** 추가 → 기존 `AcronymExpander` 확장
      (예: DTC, ECU, TPMS, ABS, 정비코드 P0xxx → 정식 명칭/설명 주입)
- [ ] 도메인 **golden eval set** 작성 → `eval/golden_vehicle.json` (recall + LLM-judge)
- [ ] 청킹/리랭커 도메인 A/B (표·수치 많은 매뉴얼 → recursive vs semantic 비교)

증거지표: 도메인 golden에서 recall, answer faithfulness 향상치.

## Phase 2 — 도메인 파인튜닝 (LoRA/QLoRA)  [로드맵 31]

Python 사이드카. JD의 "PEFT, LoRA" 직접 대응.

- [ ] `ml/data/build_dataset.py` — 코퍼스 → instruction 데이터셋(JSONL, train/val 분할)
- [ ] `ml/finetune/train_lora.py` — `transformers + peft + trl(SFTTrainer)`, QLoRA(4bit) 옵션
- [ ] base 후보: 한국어 강한 경량 모델 (예: Qwen2.5-7B-Instruct / Gemma-2-9B / Llama-3.1-8B 계열)
- [ ] LoRA 어댑터 학습 → 평가(도메인 golden로 베이스 vs 파인튜닝 비교)
- [ ] 어댑터 머지 → 다음 단계(경량화)로 전달

산출물: LoRA 어댑터, before/after 평가표, 학습 카드(하이퍼파라미터·손실곡선).

## Phase 3 — 경량화 · 추론 최적화  [로드맵 32]

JD의 "경량화 + 온디바이스/클라우드 대응 + Inference API". **벤치마크가 핵심 산출물.**

- [ ] **온디바이스 경로**: 머지 모델 → GGUF 변환 + Q4_K_M/Q5 양자화 → Ollama로 서빙
- [ ] **클라우드 경로**: AWQ/GPTQ 양자화 → vLLM 서빙(고처리량)
- [ ] `ml/optimize/benchmark.py` — TTFT, tok/s, p50/p95 지연, 메모리, 품질(golden) 측정
- [ ] **기존 Prometheus 지연 히스토그램**(`miniwatson.llm.latency`)으로 before/after 대시보드화

증거지표: 양자화 전후 지연/메모리/품질 트레이드오프 표 + Grafana 패널.

## Phase 4 — Agent + vLLM 배포 (Inference API)  [로드맵 33]

JD의 "Agentic Search/업무자동화 + 배포 가능한 모듈 + Inference API".

- [ ] `ml/serve/` — vLLM OpenAI 호환 서버(Dockerfile + compose), 파인튜닝·양자화 모델 로드
- [ ] **`VllmLlmClient implements RawLlmProvider`** 추가 → `@ConditionalOnProperty(llm.provider=vllm)`
      (OllamaLlmClient와 동일 패턴, `/v1/chat/completions` 호출)
- [ ] `application.yaml`에 `vllm:` 블록 + `.env.example` 항목 추가
- [ ] **`AgentController`** — 툴콜 오케스트레이션: RAG검색 + text-to-SQL(이미 있음) + 계산기 등
      → "Agentic Search" 데모 (질문→도구선택→근거→답변)
- [ ] CI에 vllm 프로파일 스모크 테스트 + docker-compose.vehicle.yml

증거지표: end-to-end 데모(자동차 정비 질의 → Agent가 매뉴얼 RAG + 리콜 SQL 조합 답변).

---

## 권장 진행 순서

`P0(데이터) → P1(RAG 도메인화) → P2(LoRA) → P3(경량화/벤치) → P4(Agent/vLLM 배포)`

각 Phase가 독립적으로 "동작하는 산출물 + 증거지표"를 남기도록 설계 (포트폴리오/면접용).

## 디렉터리 추가안

```
ml/
  data/      build_dataset.py, sources.md, ingest_vehicle.sh
  finetune/  train_lora.py, configs/, eval_adapter.py
  optimize/  quantize_gguf.sh, quantize_awq.py, benchmark.py
  serve/     vllm_server.Dockerfile, docker-compose.vehicle.yml
data/vehicle/            # 코퍼스 (gitignore)
eval/golden_vehicle.json # 도메인 평가셋
src/.../service/llm/VllmLlmClient.java   # 신규 provider
src/.../controller/AgentController.java   # 신규 Agent
```

## 확정 사항 (스택)

- **Base 모델**: `Qwen2.5-1.5B-Instruct` (Apache-2.0, 한국어 양호, 로컬 학습·추론 현실적).
- **컴퓨팅**: 로컬 GPU 없음(Mac/CPU) → **온디바이스 컨셉**으로 포지셔닝.
  Apple Silicon이면 MLX-LM LoRA(로컬), Intel이면 학습 런만 무료 Colab.
- **경량화/서빙**: GGUF Q4 → Ollama(온디바이스 우선). vLLM은 고처리량 데모 옵션.

> 참고: "작은 모델 + LoRA + 4bit 양자화로 노트북에서 구동" = JD의 *온디바이스 대응*에 직결.

---

## 1주일 스프린트 (Day-by-Day)

| Day | Phase | 산출물 | GPU |
|---|---|---|---|
| 1 | P0 | 매뉴얼 PDF + 리콜 CSV `vehicle` 인제스트, `ml/data/sources.md` | ❌ |
| 2 | P1 | 자동차 약어/DTC 사전 wiring(`AcronymExpander`) + `eval/golden_vehicle.json` 실데이터 + 베이스 RAG 점수 | ❌ |
| 3 | P2 | `build_dataset.py` 코퍼스→instruction JSONL, train/val | ❌ |
| 4 | P2 | `train_lora.py` LoRA/QLoRA 학습 + base vs FT 평가표 | (MLX/Colab) |
| 5 | P3 | GGUF Q4 양자화 + `benchmark.py`(tok/s·지연·메모리·품질) + Grafana before/after | ❌ |
| 6 | P4 | `VllmLlmClient`(또는 Ollama로 FT모델) + `AgentController`(RAG+text-to-SQL 툴콜) | ❌ |
| 7 | — | 통합 데모 + README 로드맵 30–33 + 스크린샷/영상 | ❌ |

> 리스크: Day4(파인튜닝)만 컴퓨팅 의존. 나머지는 기존 앱 위에서 GPU 없이 진행.
> 각 Day는 "동작 산출물 + 증거지표"를 남긴다(면접/포트폴리오용).

## 진행 현황

- [x] Day 0 — ML 스캐폴드(`ml/`), 데이터 소스 정리, 자동차 약어 시드, golden 시작본, gitignore
- [ ] Day 1 — `vehicle` 코퍼스 인제스트 (사용자: 매뉴얼 PDF 다운로드 필요)
- [ ] Day 2 — 약어사전 wiring + golden 실데이터 + 베이스 점수
- [ ] Day 3–7 — 위 표 참조
