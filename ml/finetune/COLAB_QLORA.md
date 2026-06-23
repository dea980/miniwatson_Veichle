# 7B QLoRA 파인튜닝 런북 (Colab/Kaggle → 로컬 Q4)

> 왜: 1.5B(MLX)는 한국어 다중사실 종합에서 중국어 누수·반복 퇴화(docs/RESULTS.md 2.1). 7B로 키우되
> fp16은 T4 16GB에 안 들어가 **QLoRA(4bit 베이스 + LoRA)** 로 학습. **학습=Colab 무료 GPU, 추론=로컬 M2(Q4 GGUF)**.
> 스택이 둘로 나뉜다: 학습은 NVIDIA(transformers/peft/bitsandbytes), 서빙은 로컬 Ollama.

## 0. 데이터 준비 (로컬, 학습 전)

도메인 Q&A + **진단서 형식 예시**를 합쳐 하나의 학습셋으로. 진단서 예시(`report_seed.jsonl`)를 넣어야 진단서 작업이 in-distribution이 된다.

```bash
cd ml/data
# (선택) 도메인 Q&A 재생성 — 강한 선생모델 + CJK 필터
GEN_MODEL=qwen2.5:7b-instruct python3 build_dataset.py --n-per-chunk 2 --max-chunks 200
# 도메인 Q&A + 진단서 시드 병합
cat out/train.jsonl report_seed.jsonl > out/train_7b.jsonl
cp out/val.jsonl out/val_7b.jsonl
wc -l out/train_7b.jsonl   # 학습 행 수 확인
```

`out/train_7b.jsonl`, `out/val_7b.jsonl` 두 파일을 Colab에 업로드.

## 1. Colab (무료 T4) — QLoRA 학습

런타임 → 유형 변경 → **T4 GPU**. 그 다음 셀:

```python
# 1) 의존성
!pip install -q -U transformers peft trl bitsandbytes accelerate datasets

# 2) 데이터 + 스크립트 업로드 (왼쪽 파일창에 train_7b.jsonl, val_7b.jsonl, train_qlora.py 드롭)
from google.colab import files; files.upload()   # 또는 드래그앤드롭

# 3) 학습 (7B QLoRA, T4에서 수 시간)
!python train_qlora.py \
    --base Qwen/Qwen2.5-7B-Instruct \
    --data train_7b.jsonl --val val_7b.jsonl \
    --out adapters_7b --epochs 3 --rank 16
```

> Kaggle도 동일 코드(주 30h 무료, 끊김 덜함). 13B 시도 시 `--base Qwen/Qwen2.5-14B-Instruct` + `--max-seq 768`(메모리↓), 단 T4는 빠듯 → L4/A100 권장.

## 2. Colab — 어댑터를 베이스에 merge (GGUF 변환 준비)

```python
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import PeftModel
base = "Qwen/Qwen2.5-7B-Instruct"
m = AutoModelForCausalLM.from_pretrained(base, torch_dtype=torch.float16, device_map="auto")
m = PeftModel.from_pretrained(m, "adapters_7b")
m = m.merge_and_unload()                      # LoRA를 가중치에 합침
m.save_pretrained("vehicle-7b-merged")
AutoTokenizer.from_pretrained(base).save_pretrained("vehicle-7b-merged")
# 압축해서 다운로드 (또는 HF Hub로 push)
!cd vehicle-7b-merged && tar czf ../vehicle-7b-merged.tar.gz . && cd ..
from google.colab import files; files.download("vehicle-7b-merged.tar.gz")
```

대안(권장): HF Hub로 push해서 로컬에서 받기 — `m.push_to_hub("USER/vehicle-7b")`.

## 3. 로컬 M2 — Q4 양자화 + Ollama 등록 (온디바이스)

merge된 safetensors를 받아 **Ollama 네이티브 import로 Q4_K_M 양자화** (llama.cpp convert 불필요).

```bash
mkdir -p ml/finetune/merged_7b && tar xzf vehicle-7b-merged.tar.gz -C ml/finetune/merged_7b
cat > ml/finetune/Modelfile_7b <<'EOF'
FROM ./merged_7b
EOF
ollama create vehicle-qwen2.5-7b --quantize q4_K_M -f ml/finetune/Modelfile_7b
ollama list   # vehicle-qwen2.5-7b ~4.7GB 확인
```

## 3.5 (선택) DPO 정렬 튜닝 — SFT 다음 단계

SFT(위)가 "뭘 말할지"를 가르쳤다면, DPO는 "두 답 중 뭐가 나은지"를 가르쳐 **관찰된 실패(중국어 누수·숫자 환각·언어 혼용)를 교정**한다. RLHF와 달리 리워드 모델·RL 루프 없이 **선호쌍 + 대조 손실**로 정책을 직접 최적화(싸고 안정적). 코드: `train_dpo.py`.

선호쌍 데이터 `ml/data/pref_seed.jsonl` (한 줄 = chosen/rejected):
```json
{"prompt":"KONA 리콜 요약","chosen":"한국어+근거+숫자 정확한 답","rejected":"중국어 누수/환각 답(1.5B 실패 그대로)"}
```
- chosen = 한국어·매뉴얼 근거·숫자 정확 / rejected = 우리가 관찰한 실패 모드. 10~30쌍이면 0→1 데모로 충분.

```python
# SFT 어댑터(adapters_7b) 위에서 정렬 → adapters_dpo
!python train_dpo.py --base Qwen/Qwen2.5-7B-Instruct \
    --sft-adapter adapters_7b --data ../data/pref_seed.jsonl \
    --out adapters_dpo --beta 0.1
```
핵심 손잡이: `beta`(KL 강도)와 작은 lr(5e-6) — "SFT를 안 망가뜨리며 선호로 살짝 민다". 이후 merge→Q4는 §2~3과 동일(어댑터만 `adapters_dpo`로).

## 4. 검증 + 벤치 (1.5B와 비교)

```bash
# application.yaml chat-models 화이트리스트에 vehicle-qwen2.5-7b 추가 후 백엔드 재시작
# UI 차량 진단 리포트에서 1.5B vs 7B FT 같은 차종으로 생성 → 중국어 누수·환각 비교
python3 ml/optimize/benchmark.py    # TTFT·tok/s·메모리 (Q4 7B vs Q4 1.5B)
```

기대: 7B FT는 1.5B의 **중국어 누수·반복 퇴화 제거** + 도메인 말투 유지. tok/s는 1.5B보다 느리지만 24GB M2에서 충분히 온디바이스 범위.

## 3.6 실행 인사이트 — Colab에서 실제로 막혔던 것들

라이브러리(transformers/TRL/datasets)가 빠르게 바뀌어, 코드보다 **버전·경로**에서 먼저 막힌다. 실측 정리:

| 증상 | 원인 | 해결 |
|---|---|---|
| `FileNotFoundError: .../train_7b.jsonl` | SFT 데이터(`ml/data/out/`)가 gitignore라 Colab clone에 없음 | 파일 업로드(`files.upload()`) 또는 gitignore 예외로 커밋 |
| `SFTConfig got unexpected keyword 'max_seq_length'` | 최신 TRL에서 `max_seq_length`→`max_length` 리네임 | 인자명 교체(설치 버전 기준) |
| `FileNotFoundError: .../finetune/../content/.../pref_seed.jsonl` | `--data` 상대경로 기본값이 cwd에 따라 꼬임 | 스크립트 기준 절대경로 기본값 |
| HF Hub 느림/레이트리밋 경고 | 비인증 다운로드 | `HF_TOKEN` 설정(선택, 데모는 무시 가능) |

교훈: **학습 코드가 맞아도 환경(버전·경로·데이터 위치)에서 절반은 막힌다.** 경로 기본값은 `__file__` 기준 절대경로로, 데이터 위치는 "clone에 포함되는가"를 먼저 확인. 지표 해석은 [docs/DPO_ALIGNMENT.md](../../docs/DPO_ALIGNMENT.md) §6.5.

## 메모

- **스택 분리**: 학습(CUDA/PyTorch)과 서빙(로컬 Ollama)이 다른 환경 — 어댑터/merged만 이동.
- **CJK 필터**: `build_dataset.py`가 중국어 누수 행을 학습 전에 제거(오염 차단). 누수의 근본 대응.
- **early stopping**: `load_best_model_at_end`로 val 최저 체크포인트 자동 채택(1.5B 때 수동으로 했던 것의 자동화).
- **데이터 주권**: 베이스를 HF에서 받아 사내/로컬에서 학습·서빙 가능 — 외부로 데이터 안 나감(H-Chat 정합).
