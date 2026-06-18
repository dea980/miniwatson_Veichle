#!/usr/bin/env python3
"""
P2 — LoRA/QLoRA 파인튜닝. 두 백엔드 지원.

  --backend mlx : Apple Silicon 로컬(맥 GPU/Metal). NVIDIA 불필요. ★ GPU 없는 맥 권장
  --backend hf  : CUDA/Colab(transformers+peft+trl, QLoRA 4bit)

데이터: ml/data/out/train.jsonl, val.jsonl  (build_dataset.py 출력)
Base  : Qwen2.5-1.5B-Instruct (Apache-2.0)

사용(MLX, 맥):
  python3 ml/data/build_dataset.py                 # 데이터 먼저
  python3 ml/finetune/train_lora.py --backend mlx --iters 600
  # → adapters/ 에 LoRA 어댑터 저장

사용(Colab, CUDA):
  python3 ml/finetune/train_lora.py --backend hf --epochs 3
"""
import argparse, json, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "..", "data", "out")
ADAPTERS = os.path.join(HERE, "adapters")
BASE_DEFAULT = "Qwen/Qwen2.5-1.5B-Instruct"


def to_chat(row):
    """build_dataset 행 → 채팅 포맷 텍스트."""
    user = row["instruction"] + (("\n" + row["input"]) if row.get("input") else "")
    return user, row["output"]


def prep_mlx_data():
    """MLX-LM은 {data}/train.jsonl 의 {"text": ...} 또는 {"prompt","completion"} 사용."""
    mlx_dir = os.path.join(DATA, "mlx")
    os.makedirs(mlx_dir, exist_ok=True)
    for split in ("train", "valid"):
        src = os.path.join(DATA, ("val.jsonl" if split == "valid" else "train.jsonl"))
        dst = os.path.join(mlx_dir, f"{split}.jsonl")
        with open(src, encoding="utf-8") as f, open(dst, "w", encoding="utf-8") as o:
            for line in f:
                if not line.strip():
                    continue
                r = json.loads(line)
                u, a = to_chat(r)
                o.write(json.dumps({"prompt": u, "completion": a}, ensure_ascii=False) + "\n")
    return mlx_dir


def run_mlx(args):
    data_dir = prep_mlx_data()
    os.makedirs(ADAPTERS, exist_ok=True)
    cmd = [
        sys.executable, "-m", "mlx_lm.lora",
        "--model", args.base,
        "--train",
        "--data", data_dir,
        "--iters", str(args.iters),
        "--batch-size", str(args.batch_size),
        "--num-layers", str(args.num_layers),
        "--adapter-path", ADAPTERS,
    ]
    print("[mlx]", " ".join(cmd))
    subprocess.run(cmd, check=True)
    print(f"[mlx] adapter → {ADAPTERS}")
    print("    테스트:  python3 -m mlx_lm.generate --model %s --adapter-path %s "
          "--prompt 'P0420 코드가 뭐야?'" % (args.base, ADAPTERS))
    print("    머지  :  python3 -m mlx_lm.fuse --model %s --adapter-path %s "
          "--save-path ml/finetune/merged" % (args.base, ADAPTERS))


def run_hf(args):
    from datasets import load_dataset
    from transformers import (AutoModelForCausalLM, AutoTokenizer,
                              BitsAndBytesConfig, TrainingArguments)
    from peft import LoraConfig
    from trl import SFTTrainer
    import torch

    tok = AutoTokenizer.from_pretrained(args.base)
    tok.pad_token = tok.pad_token or tok.eos_token

    bnb = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4",
                             bnb_4bit_compute_dtype=torch.bfloat16,
                             bnb_4bit_use_double_quant=True)
    model = AutoModelForCausalLM.from_pretrained(
        args.base, quantization_config=bnb, device_map="auto")

    def fmt(r):
        msgs = [{"role": "user", "content": r["instruction"] + (("\n"+r["input"]) if r["input"] else "")},
                {"role": "assistant", "content": r["output"]}]
        return {"text": tok.apply_chat_template(msgs, tokenize=False)}

    ds = load_dataset("json", data_files={
        "train": os.path.join(DATA, "train.jsonl"),
        "val": os.path.join(DATA, "val.jsonl")}).map(fmt)

    peft_cfg = LoraConfig(r=16, lora_alpha=32, lora_dropout=0.05, bias="none",
                          task_type="CAUSAL_LM",
                          target_modules=["q_proj","k_proj","v_proj","o_proj",
                                          "gate_proj","up_proj","down_proj"])
    targs = TrainingArguments(output_dir=ADAPTERS, num_train_epochs=args.epochs,
                              per_device_train_batch_size=args.batch_size,
                              gradient_accumulation_steps=4, learning_rate=2e-4,
                              logging_steps=10, save_strategy="epoch", bf16=True)
    trainer = SFTTrainer(model=model, args=targs, peft_config=peft_cfg,
                         train_dataset=ds["train"], eval_dataset=ds["val"],
                         dataset_text_field="text", max_seq_length=1024,
                         tokenizer=tok)
    trainer.train()
    trainer.save_model(ADAPTERS)
    print(f"[hf] adapter → {ADAPTERS}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", choices=["mlx", "hf"], default="mlx")
    ap.add_argument("--base", default=BASE_DEFAULT)
    ap.add_argument("--iters", type=int, default=600)      # mlx
    ap.add_argument("--epochs", type=int, default=3)       # hf
    ap.add_argument("--batch-size", type=int, default=2)
    ap.add_argument("--num-layers", type=int, default=8)   # mlx: 마지막 N층만 LoRA
    args = ap.parse_args()
    (run_mlx if args.backend == "mlx" else run_hf)(args)


if __name__ == "__main__":
    main()
