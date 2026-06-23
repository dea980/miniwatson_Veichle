# 분산 학습 런북 + 이해 (DDP / FSDP / DeepSpeed) — Kaggle 2× T4

JD 우대사항(분산 학습)을 **무료(Kaggle 2× T4)** 로 실제 돌려보고 이해하는 가이드.

## 0. 핵심 개념 한 장 — 언제 무엇을

문제: GPU 1개로 부족할 때 두 가지 부족이 있다 — **(a) 속도(처리량)** 와 **(b) 메모리(모델이 안 들어감)**.

| 방식 | 무엇을 하나 | 해결 | 언제 |
|---|---|---|---|
| **DDP** (DistributedDataParallel) | 모델을 **각 GPU에 복제**, 데이터 나눠 학습, 그래드 all-reduce 동기 | **속도**(처리량 ↑) | 모델이 1-GPU에 *들어갈 때* (1.5B 등) |
| **FSDP** (Fully Sharded DP) | 모델·그래드·옵티마이저를 **GPU에 샤딩(쪼갬)** | **메모리**(큰 모델 적재) | 모델이 1-GPU에 *안 들어갈 때* |
| **DeepSpeed ZeRO** | FSDP와 같은 샤딩(stage 1=옵티마이저, 2=+그래드, 3=+파라미터) + CPU/NVMe offload | 메모리(더 공격적) | 아주 큰 모델, offload 필요 |
| **Megatron-LM** | **텐서/파이프라인 병렬**(한 레이어를 GPU들에 쪼갬) | 초거대(수백 B) | 단일 레이어도 안 들어가는 규모 |

직관: **DDP = 복제(속도), FSDP/ZeRO = 샤딩(메모리), Megatron = 레이어까지 쪼갬(초거대).**

## 1. 우리 경우 — 정직한 프레이밍

1.5B는 **1-GPU로도 된다.** 그래서 여기서 분산은 *"필요해서"가 아니라 메커니즘 시연*이다(DPO 10쌍 0→1과 같은 정직한 논리). 보여주는 것:
- DDP로 **2-GPU 처리량 2배** (실제 효용).
- FSDP로 **샤딩 메커니즘** (큰 모델일 때의 그림을 1.5B로 시연).

"진짜 필요"는 7B+ 를 fp16 풀파인튜닝할 때 — 그건 GPU 여러 장(스케일 칸).

## 2. 코드는 거의 안 바뀐다 (핵심 학습 포인트)

분산 코드를 직접 안 쓴다. **`accelerate launch` + config 파일**이 처리하고, HF `Trainer`(SFTTrainer)가 accelerate 환경을 자동 인지한다. `train_distributed.py`에서 분산 관련은:
- `device_map` **제거**(DDP/FSDP와 충돌 — device_map은 순진한 모델병렬).
- 저장은 `is_main_process`만.
그 외는 단일 학습과 동일. → "분산은 *런처/설정*의 문제지 모델 코드의 문제가 아니다"가 깨달음.

## 3. 실행 (Kaggle, Accelerator = GPU T4 ×2)

```python
# 1) 의존성 + 데이터 업로드(train.jsonl, val.jsonl — Colab과 동일)
!pip install -U transformers peft trl accelerate datasets

# 2) DDP 먼저 (가장 robust)
!accelerate launch --config_file ml/finetune/accel_ddp.yaml \
    ml/finetune/train_distributed.py --data train.jsonl --val val.jsonl

# 3) FSDP (샤딩 시연)
!accelerate launch --config_file ml/finetune/accel_fsdp.yaml \
    ml/finetune/train_distributed.py --data train.jsonl --val val.jsonl
```

## 4. 무엇을 관찰하나 (증거)

- 로그 `[dist] world_size=2` → 2-GPU로 돈다는 증거.
- `nvidia-smi`(다른 셀) → **두 GPU 다 사용률↑**.
- **유효 배치 = per_device(1) × accum(8) × world_size(2) = 16** — GPU 늘면 유효배치 자동 증가.
- DDP vs 단일: 같은 step을 ~절반 시간에(처리량 2배). FSDP: GPU당 메모리↓(샤딩).

## 5. 함정 (실측 대비)

- **device_map="auto" 금지** — DDP/FSDP와 충돌. 빼야 함.
- **T4 = fp16** (bf16은 Ampere+). config·모델 로드 둘 다 fp16.
- **FSDP wrap 클래스명** — `fsdp_transformer_layer_cls_to_wrap`를 베이스 디코더층에 맞춤(Qwen=`Qwen2DecoderLayer`, EXAONE은 그 모델의 디코더층 클래스).
- **QLoRA(4bit)+FSDP**는 까다로움 — 데모는 **bf16/fp16 LoRA**로 단순화. 4bit+FSDP는 별도 설정 필요(스케일 칸).
- Kaggle 인터넷 ON(모델 다운로드), 세션 12h.

## 6. DeepSpeed로 바꾸려면 (참고)

`accelerate config`에서 `distributed_type: DEEPSPEED` + `zero_stage: 3`(+ optional `offload_optimizer: cpu`)로 바꾸면 ZeRO-3. 개념은 FSDP와 동일(샤딩), offload가 더 공격적. 우리 스크립트는 그대로.

## 7. 면접 한 줄

> "1.5B는 1-GPU로도 되지만, **Kaggle 2× T4에서 DDP(복제·처리량)와 FSDP(샤딩·메모리)를 accelerate로 실제 구성·실행**해봤다. 분산은 모델 코드가 아니라 *런처/설정*의 문제이고, device_map 충돌·T4 fp16·wrap 클래스 같은 실측 함정을 겪었다. ZeRO/Megatron은 더 큰 규모에서 *왜* 필요한지(속도 vs 메모리 vs 레이어 분할) 이해하고 있다."
