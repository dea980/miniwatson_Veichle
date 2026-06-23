#!/usr/bin/env python3
"""
7B QLoRA 파인튜닝 (Colab/Kaggle CUDA 경로).

왜 이 경로인가:
- 1.5B(MLX 로컬)는 한국어 다중사실 종합에서 중국어 누수·반복 퇴화 → 용량 한계(docs/RESULTS.md 2.1).
- 7B로 키우되, fp16이면 T4 16GB에 안 들어가므로 **QLoRA(4bit 베이스 위 LoRA)** 로 학습.
- 학습=Colab/Kaggle 무료 GPU, 추론=로컬 M2(Q4 GGUF). MLX와 달리 NVIDIA 스택.

산출물: adapters_7b/ (LoRA 어댑터). 로컬에서 merge → GGUF Q4 → Ollama (COLAB_QLORA.md 참고).

실행(Colab 셀 또는):
  pip install -U transformers peft trl bitsandbytes accelerate datasets
  python train_qlora.py --data train.jsonl --val val.jsonl --base Qwen/Qwen2.5-7B-Instruct
"""
import argparse, json, os
import torch
from datasets import load_dataset
from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig
from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training
from trl import SFTConfig, SFTTrainer

SYSTEM = "당신은 현대자동차 정비·진단 한국어 어시스턴트입니다. 한국어로만, 근거에 충실하게 답하세요."


def build_text(tokenizer, row):
    """instruction/input/output → Qwen chat 템플릿 한 문자열로."""
    user = row["instruction"] + (("\n\n" + row["input"]) if row.get("input") else "")
    msgs = [{"role": "system", "content": SYSTEM},
            {"role": "user", "content": user},
            {"role": "assistant", "content": row["output"]}]
    return tokenizer.apply_chat_template(msgs, tokenize=False)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="Qwen/Qwen2.5-7B-Instruct")
    ap.add_argument("--data", default="train.jsonl")
    ap.add_argument("--val", default="val.jsonl")
    ap.add_argument("--out", default="adapters_7b")
    ap.add_argument("--epochs", type=float, default=3.0)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--batch", type=int, default=1)
    ap.add_argument("--accum", type=int, default=8)      # 유효 배치 = batch*accum
    ap.add_argument("--rank", type=int, default=16)
    ap.add_argument("--max-seq", type=int, default=1024)
    args = ap.parse_args()

    # 4bit 양자화 로드 (QLoRA 핵심: 얼린 베이스를 4bit nf4로 → T4 16GB에 7B가 올라감)
    bnb = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_compute_dtype=torch.bfloat16,
        bnb_4bit_use_double_quant=True,
    )
    tok = AutoTokenizer.from_pretrained(args.base)
    if tok.pad_token is None:
        tok.pad_token = tok.eos_token
    model = AutoModelForCausalLM.from_pretrained(
        args.base, quantization_config=bnb, device_map="auto", torch_dtype=torch.bfloat16)
    model = prepare_model_for_kbit_training(model)

    lora = LoraConfig(
        r=args.rank, lora_alpha=args.rank * 2, lora_dropout=0.05, bias="none",
        task_type="CAUSAL_LM",
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj",
                        "gate_proj", "up_proj", "down_proj"],
    )
    model = get_peft_model(model, lora)
    model.print_trainable_parameters()

    # 데이터: jsonl → chat 텍스트 컬럼
    ds = load_dataset("json", data_files={"train": args.data, "val": args.val})
    ds = ds.map(lambda r: {"text": build_text(tok, r)})

    cfg = SFTConfig(
        output_dir=args.out, num_train_epochs=args.epochs,
        per_device_train_batch_size=args.batch, gradient_accumulation_steps=args.accum,
        learning_rate=args.lr, lr_scheduler_type="cosine", warmup_ratio=0.05,
        logging_steps=10, eval_strategy="epoch", save_strategy="epoch",
        bf16=True, max_length=args.max_seq, dataset_text_field="text",   # TRL 최신: max_seq_length → max_length
        report_to="none", load_best_model_at_end=True, metric_for_best_model="eval_loss",
    )
    trainer = SFTTrainer(model=model, args=cfg,
                         train_dataset=ds["train"], eval_dataset=ds["val"],
                         processing_class=tok)
    trainer.train()
    trainer.save_model(args.out)        # LoRA 어댑터 저장
    tok.save_pretrained(args.out)
    print(f"[done] adapter → {args.out}  (val 최저 체크포인트 = early stopping 효과)")


if __name__ == "__main__":
    main()
