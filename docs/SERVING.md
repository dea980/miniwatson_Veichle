# 모델 서빙 — 스택 사다리, vLLM, 최적화, 로드맵

학습(LoRA/QLoRA/DPO)과 별개로 **추론 서빙**을 어떻게 키우는지 정리한다. 이 프로젝트의 서빙 직무(vLLM/sglang/TRT-LLM/Triton, prefix-aware routing, KV cache 최적화, 대규모 트래픽) 매핑과 실현 로드맵.

## 1. 현재 — Ollama(llama.cpp) 온디바이스

- 단일 노드, M2(Metal). llama.cpp가 KV cache·프롬프트(프리픽스) 캐시·`--parallel` 슬롯·연속배칭(`-cb`)을 일부 지원.
- 강점: 제로 인프라, 데이터 주권(외부 전송 없음), 데모 적합.
- 한계: 처리량·동시성 한계, 대규모 트래픽엔 부족. 멀티 GPU·분산 없음.

## 2. 서빙 스택 사다리

| 단계 | 엔진 | 어디서 | 핵심 |
|---|---|---|---|
| 지금 | **Ollama**(llama.cpp) | M2 온디바이스 | KV cache, 일부 배칭, 제로 인프라 |
| 로컬 다음 | **vLLM-Metal** | M2(Apple Silicon) | PagedAttention·KV cache·OpenAI API를 **맥에서** |
| 프로덕션 | **vLLM**(CUDA) | 클라우드 GPU(T4~A100) | 연속배칭·PagedAttention·prefix caching, 고처리량 |
| 고성능 | **sglang** | GPU | **RadixAttention(프리픽스 트리 캐시)**, 구조적 디코딩 |
| 엔터프라이즈 | **Triton + TensorRT-LLM** | GPU 팜 | 커널 최적화·멀티모델 서빙·동적 배칭·노드 효율 |

원칙: 작게는 Ollama, 트래픽이 오면 vLLM(연속배칭), 프리픽스 재사용이 크면 sglang, 운영 규모면 Triton/TRT-LLM. **학습 예산(VRAM)과 서빙 예산(처리량·지연)은 별개** ([DECISIONS.md](DECISIONS.md) §7.5).

## 3. vLLM-Metal — Apple Silicon에서 vLLM (2025~2026)

이전 통념("vLLM은 NVIDIA CUDA 전제")은 **부분적으로 구식**이 됐다. **vLLM-Metal**(커뮤니티 + Docker 협업)이 M시리즈에서 vLLM을 돌린다.

- **MLX 백엔드**로 Metal GPU 가속(PyTorch MPS보다 빠름), unified memory 제로카피.
- **OpenAI 호환 API + PagedAttention + KV cache** 제공 — JD가 말하는 그 기능들.
- v0.2.0(2026-04) Metal 커널로 TTFT 83×·처리량 3.6× 개선. **Docker Model Runner**로도 실행.
- 제약: 텍스트 전용(비전 X), 신생(거친 부분 가능), 모델이 RAM에 올라가야(7B Q4 ~5GB).

시너지: 우리는 이미 **MLX 학습 경로(1.5B)** 가 있어, vLLM-Metal(MLX 모델 서빙)과 결이 맞는다. → **클라우드 GPU 없이 맥에서 vLLM 서빙을 실증** 가능.

## 4. 우리 패턴 → 서빙 최적화 매핑

추상적 기능이 아니라, 이 앱의 실제 패턴에 붙여 설명한다.

| 우리 패턴 | 최적화 | 왜 |
|---|---|---|
| 모든 프롬프트에 **고정 SYSTEM 프롬프트** + 매뉴얼 컨텍스트 | **prefix/prompt caching**(vLLM prefix cache, sglang RadixAttention) | 공유 프리픽스의 KV를 재계산 안 함 → TTFT↓ |
| 진단서·종합 답변 = **긴 출력**(num-predict 512) | **PagedAttention·KV cache** | 긴 시퀀스 KV 메모리 단편화 방지 |
| **멀티모델 선택**(granite/qwen/7B-DPO) | **모델 라우팅**(요청→적합 모델) | 작업별 비용/품질 최적 배분 |
| 대시보드가 **동시 쿼리 다발** | **연속배칭(continuous batching)** | GPU 활용률↑, 처리량↑ (DuckDB 동시성과 별개 계층) |

## 5. JD 요구 → 현재/계획/스케일 매핑

| JD 요구 | 현재 | 계획(이 프로젝트로 실증) | 스케일(설계만) |
|---|---|---|---|
| Alignment Tuning | ✅ SFT→DPO 실측 | 선호쌍 확대 재학습 | RLHF/PPO 비교 |
| 모델 서빙(vLLM…) | Ollama | **vLLM-Metal 로컬 서빙 + provider** | vLLM CUDA 클라우드 |
| prefix routing·KV cache | (llama.cpp 일부) | **prefix cache 벤치 + 라우터** | sglang RadixAttention |
| 대규모 트래픽 | 단일 노드 | **부하 테스트(p50/p95·처리량)** | Triton 멀티노드·동적배칭 |
| GPU/노드 효율 | 온디바이스 | 배칭/양자화 효율 측정 | TRT-LLM 커널·멀티모델 |
| 데이터 전처리 자동화 | fetch/build/ingest 스크립트 | 파이프라인 문서화 | Airflow/Prefect 오케스트레이션 |

정직 원칙: **직접 해본 것(DPO·앱·데이터)은 실증**, **vLLM은 vLLM-Metal로 실제 붙여 실증**, **Triton/TRT-LLM은 "왜·어떻게 키울지"를 설계로** — half-built보다 "이해 + 신뢰 가능한 스케일 경로"가 강하다.

## 6. 로드맵

- **P1 — vLLM-Metal 로컬 서빙(실증)**: vllm-metal 설치(또는 Docker Model Runner) → 7B(또는 DPO-merged) OpenAI API(`:8000`). 작동 확인.
- **P2 — 앱 provider 연결 ✅**: `llm.provider=vllm`(OpenAI 호환) 배선 완료. `VllmLlmClient`(`/v1/chat/completions`, 한국어 가드 system·타임아웃·비전 image_url)가 `@ConditionalOnProperty(llm.provider=vllm)`로 활성, 기존 `RawLlmProvider` 추상화에 그대로 끼워짐(거버넌스 래퍼 `OllamaService`가 감쌈 — 호출처 변경 0). 설정 `vllm.url/api-key/chat-model/chat-models/num-predict`(application.yaml). [LLM-ABSTRACTION.md](LLM-ABSTRACTION.md) 패턴.

  실행:
  ```bash
  vllm serve Qwen/Qwen2.5-7B-Instruct        # (별도 터미널, :8000) 또는 Docker Model Runner
  LLM_PROVIDER=vllm VLLM_URL=http://localhost:8000 ./mvnw spring-boot:run
  ```
- **P3 — 최적화 실측**: prefix caching on/off, 연속배칭 동시성별 처리량/지연 벤치. §4 매핑을 수치로. 스크립트 `ml/optimize/bench_serving.py`(OpenAI 호환, stdlib) — vLLM·Ollama 대조.
  ```bash
  python3 ml/optimize/bench_serving.py --base http://localhost:8000  --model Qwen/Qwen2.5-7B-Instruct   # vLLM
  python3 ml/optimize/bench_serving.py --base http://localhost:11434 --model qwen2.5:7b-instruct        # Ollama(대조)
  ```
  측정: ① prefix TTFT warm(재사용) vs cold(매번 새 프리픽스) 절감%, ② 동시성 1·2·4·8별 처리량(req/s)·p50/p95.

  **실측 (2026-07-01, MacBook Air M·24GB · Qwen2.5-7B · max_tokens 128)**

  | 지표 | vLLM-Metal (bf16) | Ollama (Q4) |
  |---|---|---|
  | Prefix TTFT warm→cold 절감 | 842→1032ms, **18.4%** | 388→507ms, **23.5%** |
  | 처리량 c=1 (req/s) | 0.08 | **0.18** |
  | 처리량 c=8 (req/s) | **0.20** | 0.09 |
  | 처리량 c=1→8 추세 | **↑ 2.5×**(배칭) | ↓ 0.5×(포화) |
  | p95 c=8 (s) | **55.4** | 97.4 |

  해석: **저부하는 Ollama(Q4)가 빠르고, 고부하는 vLLM이 역전**. 동시성을 올릴 때 처리량이 vLLM은 **오르고**(연속 배칭) Ollama는 **내린다**(포화) — 이게 vLLM을 쓰는 이유(다중 사용자·트래픽). prefix cache는 양쪽 다 TTFT ~20% 절감. **정직한 한계**: bf16 vs Q4라 절대속도는 비공정, 그러나 스케일링 추세는 정밀도 무관한 신호. 공정 비교는 동일 양자화 서빙이 과제.
- **P4 — 대규모/엔터프라이즈(설계)**: vLLM CUDA 클라우드(대규모 트래픽), sglang(프리픽스), Triton/TRT-LLM(멀티모델·노드 효율) — 스케일 칸으로 설계·문서화.

## 출처

- vLLM-Metal (GitHub) — https://github.com/vllm-project/vllm-metal
- Docker Model Runner Adds vLLM Support on macOS — https://www.docker.com/blog/docker-model-runner-vllm-metal-macos/
- Running vLLM Hello World on a Mac — https://medium.com/@alexagriffith/running-vllm-hello-world-on-a-mac-9ea03fba7ec6
- vLLM 문서(서빙·PagedAttention) — https://docs.vllm.ai/
