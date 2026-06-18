# ml/ — Vehicle ML 사이드카

Java 앱(오케스트레이션, 거버넌스)과 분리된 Python ML 워크스페이스.
파인튜닝·경량화 후 모델을 **Ollama(GGUF) 또는 vLLM**로 서빙 → Java가 `llm.provider`로 호출.

## 확정 스택 (GPU 없음 / 온디바이스)

- **Base 모델**: `Qwen2.5-1.5B-Instruct` (Apache-2.0, 한국어 양호, 로컬 학습·추론 현실적)
- **파인튜닝**: LoRA / QLoRA
  - Apple Silicon → **MLX-LM LoRA** (맥 GPU/Metal, NVIDIA 불필요)
  - Intel/그 외 → 학습 런만 무료 Colab(T4), 추론은 로컬
- **경량화**: GGUF Q4_K_M 양자화 → Ollama 서빙 (온디바이스 경로)
- **서빙**: 기본 Ollama. 고처리량 데모 시 vLLM(OpenAI 호환) 옵션

## 구조

```
ml/
  data/      build_dataset.py   코퍼스→instruction JSONL
             sources.md         데이터 소스·라이선스 (작성됨)
             ingest_vehicle.sh  data/vehicle/ → 앱 인제스트 API 호출
  finetune/  train_lora.py      MLX/peft LoRA 학습
             eval_adapter.py    base vs 파인튜닝 도메인 평가
  optimize/  quantize_gguf.sh   머지→GGUF Q4 변환
             benchmark.py       tok/s·지연·메모리·품질 측정
  serve/     vllm_server.Dockerfile, docker-compose.vehicle.yml
```

## 환경

```bash
python -m venv .venv && source .venv/bin/activate
pip install -U mlx-lm transformers datasets peft trl   # Apple Silicon: mlx-lm
```

자세한 단계는 ../VEHICLE_EXTENSION_PLAN.md 참고.
