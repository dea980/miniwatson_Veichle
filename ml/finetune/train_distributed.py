#!/usr/bin/env python3
"""
분산 학습 데모 (DDP / FSDP) — Kaggle 2× T4 무료 GPU.

목적: 멀티-GPU 학습 *메커니즘*을 실제로 돌려본다(JD 우대: 분산 학습 경험).
  - 1.5B는 1-GPU로도 되니 분산은 "필요"가 아니라 **메커니즘 시연**(DPO 10쌍 0→1과 같은 정직한 논리).
  - 코드엔 분산 코드가 거의 없다 — `accelerate launch` + config 파일이 처리하고,
    HF Trainer(SFTTrainer)가 accelerate 환경을 자동 인지한다. 이게 핵심 학습 포인트.

주의(실측 함정):
  - **device_map 금지**: device_map="auto"는 순진한 모델 병렬이라 DDP/FSDP와 충돌. 빼고 accelerate에 맡긴다.
  - **T4는 fp16**: bf16은 Ampere+ 전용. T4(Turing)는 fp16으로.

실행 (Kaggle 셀, accelerator=GPU T4 x2):
  pip install -U transformers peft trl accelerate datasets
  # DDP(간단, 먼저): 모델을 각 GPU에 복제 + 그래드 동기 → 처리량 2배
  accelerate launch --config_file accel_ddp.yaml  train_distributed.py --data train.jsonl --val val.jsonl
  # FSDP(샤딩): 모델/옵티마이저/그래드를 GPU에 쪼갬 → 큰 모델용
  accelerate launch --config_file accel_fsdp.yaml train_distributed.py --data train.jsonl --val val.jsonl
"""
import argparse, torch
from datasets import load_dataset
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import LoraConfig
from trl import SFTConfig, SFTTrainer

SYSTEM = "당신은 현대자동차 정비·진단 한국어 어시스턴트입니다. 한국어로만, 근거에 충실하게 답하세요."


def build_text(tok, row):
    user = row["instruction"] + (("\n\n" + row["input"]) if row.get("input") else "")
    msgs = [{"role": "system", "content": SYSTEM},
            {"role": "user", "content": user},
            {"role": "assistant", "content": row["output"]}]
    return tok.apply_chat_template(msgs, tokenize=False)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="Qwen/Qwen2.5-1.5B-Instruct")
    ap.add_argument("--data", default="train.jsonl")
    ap.add_argument("--val", default="val.jsonl")
    ap.add_argument("--out", default="adapters_dist")
    ap.add_argument("--epochs", type=float, default=2.0)
    ap.add_argument("--rank", type=int, default=16)
    args = ap.parse_args()

    tok = AutoTokenizer.from_pretrained(args.base)
    if tok.pad_token is None:
        tok.pad_token = tok.eos_token

    # device_map 없음! accelerate(DDP/FSDP)가 배치/샤딩 담당. T4라 fp16.
    model = AutoModelForCausalLM.from_pretrained(args.base, torch_dtype=torch.float16)

    lora = LoraConfig(r=args.rank, lora_alpha=args.rank * 2, lora_dropout=0.05, bias="none",
                      task_type="CAUSAL_LM",
                      target_modules=["q_proj", "k_proj", "v_proj", "o_proj",
                                      "gate_proj", "up_proj", "down_proj"])

    ds = load_dataset("json", data_files={"train": args.data, "val": args.val})
    ds = ds.map(lambda r: {"text": build_text(tok, r)})

    cfg = SFTConfig(
        output_dir=args.out, num_train_epochs=args.epochs,
        per_device_train_batch_size=1, gradient_accumulation_steps=8,   # 유효배치 = 1×8×GPU수
        learning_rate=2e-4, lr_scheduler_type="cosine", warmup_ratio=0.05,
        logging_steps=5, fp16=True, max_length=1024, dataset_text_field="text",
        report_to="none", save_strategy="epoch",
    )
    trainer = SFTTrainer(model=model, args=cfg,
                         train_dataset=ds["train"], eval_dataset=ds["val"],
                         processing_class=tok, peft_config=lora)

    # 로그에 GPU 수가 찍힌다(world_size). 유효배치 = per_device × accum × world_size.
    print(f"[dist] world_size={trainer.accelerator.num_processes}, device={trainer.accelerator.device}")
    trainer.train()

    if trainer.accelerator.is_main_process:          # 저장은 메인 프로세스만(중복 방지)
        trainer.save_model(args.out)
        tok.save_pretrained(args.out)
        print(f"[done] adapter → {args.out}")


if __name__ == "__main__":
    main()
