#!/usr/bin/env python3
"""
DPO 정렬 튜닝 (SFT 다음 단계) — 스켈레톤.

배경: SFT(LoRA/QLoRA)는 "뭘 말할지"를 가르치고, DPO는 "두 답 중 뭐가 나은지"를 가르친다.
      RLHF와 달리 리워드 모델·RL 루프 없이 선호쌍 + 대조 손실로 정책을 직접 최적화.
목표: 1.5B가 보인 실패(중국어 누수·숫자 환각·언어 혼용)를 rejected로 둬 정렬로 교정.

흐름: train_qlora.py(SFT) → [이 파일] DPO → merge → quantize (COLAB_QLORA.md 흐름에 한 칸 추가)
실행(Colab/CUDA): pip install -U transformers peft trl bitsandbytes accelerate datasets
                  python train_dpo.py --base Qwen/Qwen2.5-7B-Instruct --sft-adapter adapters_7b \
                                      --data ../data/pref_seed.jsonl --out adapters_dpo

────────────────────────────────────────────────────────────────────────
구현 가이드 (네가 채울 TODO 3곳):
  TODO-1  선호 데이터 → DPO 형식 매핑  (prompt / chosen / rejected)
  TODO-2  DPOConfig 하이퍼파라미터    (특히 beta = KL 강도)
  TODO-3  DPOTrainer 조립 + 학습
참고: TRL DPOTrainer 문서 — https://huggingface.co/docs/trl/dpo_trainer
"""
import argparse, torch
from datasets import load_dataset
from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig
from peft import PeftModel, LoraConfig
from trl import DPOTrainer, DPOConfig   # TODO: import 풀기

SYSTEM = "당신은 현대자동차 정비, 진단 한국어 어시스턴트입니다. 한국어로만, 근거에 충실하게 답하세요."


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="Qwen/Qwen2.5-7B-Instruct")
    ap.add_argument("--sft-adapter", default="adapters_7b", help="train_qlora.py 산출 LoRA 어댑터(SFT 체크포인트)")
    ap.add_argument("--data", default="../data/pref_seed.jsonl")
    ap.add_argument("--out", default="adapters_dpo")
    ap.add_argument("--beta", type=float, default=0.1)
    ap.add_argument("--epochs", type=float, default=1.0)
    args = ap.parse_args()

    # ── 보일러플레이트 (제공): 4bit 베이스 + SFT 어댑터를 정책으로 로드 ──
    bnb = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4",
                             bnb_4bit_compute_dtype=torch.bfloat16, bnb_4bit_use_double_quant=True)
    tok = AutoTokenizer.from_pretrained(args.base)
    if tok.pad_token is None:
        tok.pad_token = tok.eos_token
    base = AutoModelForCausalLM.from_pretrained(args.base, quantization_config=bnb,
                                                device_map="auto", torch_dtype=torch.bfloat16)
    # 정책 = base + SFT 어댑터. (DPO는 이 위에서 이어서 정렬)
    model = PeftModel.from_pretrained(base, args.sft_adapter, is_trainable=True)
    # 레퍼런스 모델: LoRA면 ref_model=None → 어댑터를 끈 base가 자동 레퍼런스(KL 앵커). 메모리 절약.

    # ── TODO-1: 선호 데이터 매핑 ─────────────────────────────────────────
    # pref_seed.jsonl 한 줄 = {"prompt": "...", "chosen": "...", "rejected": "..."}
    # DPOTrainer는 보통 {prompt, chosen, rejected} 문자열 컬럼을 기대한다.
    # 힌트: prompt에 chat 템플릿(system+user)을 적용할지 결정하라.
    #   - tok.apply_chat_template([{role:system}, {role:user}], add_generation_prompt=True)
    #   - chosen/rejected는 assistant 답변 텍스트(그대로 or 템플릿 정합)
    ds = load_dataset("json", data_files={"train": args.data})["train"]
    def to_dpo(row):
        msgs = [{"role" : "system", "content": SYSTEM},
                {"role" : "user", "content": row["prompt"]}]
        return {
            "prompt": tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True),
            "chosen": row["chosen"],
            "rejected": row["rejected"],
        }
    ds = ds.map(to_dpo)

    # def to_dpo(row): ...  return {"prompt":..., "chosen":..., "rejected":...}
    # ds = ds.map(to_dpo)
    # raise NotImplementedError("TODO-1: prompt/chosen/rejected 매핑을 구현하세요")

    # ── TODO-2: DPOConfig ───────────────────────────────────────────────
    # beta(=KL 강도): 작을수록 레퍼런스에서 자유롭게 멀어짐(과적합 위험), 클수록 보수적. 0.1~0.5에서 탐색.
    cfg = DPOConfig(output_dir=args.out, beta=args.beta, num_train_epochs=args.epochs,
                    per_device_train_batch_size=1, gradient_accumulation_steps=8,
                    learning_rate=5e-6, lr_scheduler_type="cosine", bf16=True,
                    logging_steps=10, max_prompt_length=512, max_length=1024, report_to="none")

    # ── TODO-3: DPOTrainer 조립 + 학습 ──────────────────────────────────
    trainer = DPOTrainer(
                        model=model,
                        ref_model=None,
                        args=cfg,
                        train_dataset=ds,
                        processing_class=tok,
                         # peft_config=LoraConfig(r=16, lora_alpha=32, task_type="CAUSAL_LM",
                         #     target_modules=["q_proj","k_proj","v_proj","o_proj"]))
    )
    trainer.train()
    trainer.save_model(args.out)
    tok.save_pretrained(args.out)
    print(f"[dpo] 정렬 어댑터 → {args.out}")


if __name__ == "__main__":
    main()
