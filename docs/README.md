# Vehicle LLM Platform — 문서 인덱스 & 현황

> 데이터, AI, 거버넌스 레이어를 갖춘 기반 플랫폼(MiniWatson, watsonx 클론)을 **자동차 도메인 특화 LLM 플랫폼**으로 확장한 프로젝트.
> 현대차 NLP/LLM 직무 JD에 매핑. 이 문서는 전체 진입점 + 결정 맥락 기록.

---

## 1. 한눈에

```
[Java/Spring] RAG, 거버넌스, 서빙, text-to-SQL     ← 본체(유지)
      │  LlmClient 추상화 (ollama|watsonx|vllm…)
      ▼
[Python ml/] 데이터수집 → 파인튜닝(LoRA) → 양자화 → 벤치  ← 모델 제작(신규)
      → 완성 모델을 Ollama/vLLM로 서빙 → 본체가 HTTP 호출
```

핵심: **서빙(Java)과 모델 제작(Python)을 분리**, 모델은 `llm.provider`로 갈아끼움 = 온디바이스↔클라우드 대응.

## 2. 문서 지도

| 문서 | 내용 |
|---|---|
| [VEHICLE_ARCHITECTURE.md](VEHICLE_ARCHITECTURE.md) | 현재 아키텍처 전체 + 컴포넌트 + 기술스택 |
| [../VEHICLE_EXTENSION_PLAN.md](../VEHICLE_EXTENSION_PLAN.md) | 단계별 확장 계획(P0~P4) + 7일 스프린트 |
| [WHY_LORA.md](WHY_LORA.md) | 왜 파인튜닝/LoRA인가 (RAG와의 차이) |
| [PEFT_METHODS.md](PEFT_METHODS.md) | PEFT 방식 비교(LoRA/QLoRA/Adapter…) + 선택 가이드 |
| [INFERENCE_OPTIMIZATION.md](INFERENCE_OPTIMIZATION.md) | 양자화와 추론 최적화 + 벤치 + 자원 직렬화 |
| [SERVING.md](SERVING.md) | 서빙 스택 사다리(Ollama→vLLM-Metal→vLLM/sglang→Triton/TRT-LLM), 우리 패턴→최적화 매핑, JD 매핑, 로드맵 |
| [AGENT.md](AGENT.md) | Agentic Search(라우팅, SQL 자기수정, 모델 라우팅) + 진단서, 이미지진단, 부품견적 |
| [AS-OPERATIONS.md](AS-OPERATIONS.md) | A/S 운영 기능 — 차종/케이스 2레벨, 우선순위 트리아지, 건별 점검 체크리스트, 정비 스케줄, 리포트 개편 |
| [GRAPHRAG_VEHICLE.md](GRAPHRAG_VEHICLE.md) | GraphRAG 고도화 설계(자동차 사전 기반 경량 공출현 그래프) |
| [DESIGN.md](DESIGN.md) | UI 디자인 시스템, 토큰, 결정(현대풍 미니멀, 무프레임워크) |
| [RESULTS.md](RESULTS.md) | 동작 결과 + 정량 지표 + 의사결정 |
| [../ml/RUNBOOK.md](../ml/RUNBOOK.md) | 실행 단계별 명령어(복붙용) |
| [../ml/data/sources.md](../ml/data/sources.md) | 데이터 소스와 라이선스 |

## 3. 현재 진행 현황 (V-트랙)

- [x] **V1 데이터 수집** — NHTSA 리콜 119건(원본 JSON 보존) + Internet Archive 매뉴얼 PDF 3종
- [x] **V2 자동차 RAG** — `vehicle` 네임스페이스, accent 123청크, 한국어 답변과 근거(sources) 검증
- [x] **V3 리콜 text-to-SQL** — `/api/tabular`(DuckDB)
- [x] **V4 LoRA 파인튜닝** — MLX, early-stopping(iter-200), base vs FT
- [x] **V5 추론 최적화** — 양자화 벤치(Q4 vs Q8: 메모리 절반, 속도 2배) → Ollama 서빙
- [x] **V6 Agentic Search** — RAG/리콜SQL/BOTH 라우팅 + 트레이스 + SQL 자기수정
- [x] **V7 UI** — Next.js 7탭 + 음성(STT/TTS), 정적 UI 패리티
- [x] **V8~V10 캡스톤** — 멀티테이블(불만 추가), 차종 종합 진단서, 이미지진단→필요부품
- [ ] **V12 옵션** — vLLM provider, 임베딩 파인튜닝, 온디바이스 음성, 라이브 배포

## 4. 결정 로그 (왜 이렇게 했나 — 맥락)

1. **데이터 소스 전환** — 현대차 공홈(oms.hmc.co.kr)은 로그인과 ToS 제약 → **공개돼 있고 재현 가능한** 소스로:
   NHTSA(공개 API, 키 불필요) + Internet Archive(공개 PDF) + 공공데이터포털(사용자 키). 원문은 `data/vehicle/`(gitignore).
2. **2계층 분리** — ML 생태계는 Python뿐(transformers/peft/mlx). 서빙은 견고한 Java 자산. → 분리하고 HTTP/모델파일로 연결(MLOps 경계).
3. **작은 모델 + LoRA + 양자화** — GPU 없는 M2 제약 → Qwen2.5-1.5B + LoRA(MLX) + GGUF Q4. 이 제약이 곧 JD "온디바이스" 스토리.
4. **데이터 품질이 9할** — 약한 생성모델(granite4)로 만든 합성데이터에 음역오류와 환각("하다모", "이등신")이 섞여 → 학습 시 그대로 주입. → 생성 선생모델을 qwen2.5:7b로 키우고 발췌근거와 고유명사 규칙을 강제. (= "데이터 기반 파인튜닝" 역량)
5. **언어 처리** — 영어 매뉴얼 코퍼스 + 다국어 임베딩으로 한국어 질의 교차검색. 답변 언어는 프롬프트로 질문 언어를 따라가게 고정.
6. **네임스페이스 격리** — `vehicle`만 별도 적재. 약어 시드도 도메인 한정(다른 도메인 오염 방지).
7. **자원 직렬화** — 단일 M2에서 생성, 학습, 추론을 동시에 = 메모리 초과/크래시. → 무거운 작업 하나씩, `ollama stop`으로 회수. *운영에선 별도 노드 분리* (분리 아키텍처의 실증 근거).

## 5. JD 매핑 요약

| JD | 대응 | 상태 |
|---|---|---|
| 파인튜닝(PEFT/LoRA) | ml/finetune (MLX) | 🔄 |
| 경량화와 추론 최적화(온디바이스) | ml/optimize (GGUF Q4 + 벤치) | 🔄 |
| RAG/Agent 고도화 | vehicle RAG / AgentController | 🟢/⚪ |
| 밸류체인 NLP(판매/제조/A·S) | 매뉴얼RAG, 리콜SQL, 요약 | 🟢 |
| MLOps + Inference API | Spring API, Docker/CI, vLLM provider | 🟢/⚪ |

## 6. 다음 단계

1. 깨끗한 데이터로 LoRA 재학습 → base vs FT 정량 비교
2. 양자화 벤치 표 완성(tok/s, TTFT)
3. **UI: 정적 HTML → Next/React** + 배포(Vercel + Cloud Run, `llm.provider=watsonx` 스왑)
4. Agent(RAG+SQL 툴콜) + README 결과 정리
