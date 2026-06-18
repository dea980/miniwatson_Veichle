#!/usr/bin/env python3
"""
P2 — base vs 파인튜닝 비교 (도메인 val셋).

MLX 백엔드 기준. base 모델과 LoRA 어댑터 적용 모델로 각각 생성 →
간단 키워드/길이 휴리스틱 + (옵션) Ollama LLM-judge로 점수.

사용:
  python3 ml/finetune/eval_adapter.py --n 20
  python3 ml/finetune/eval_adapter.py --n 20 --judge
"""
import argparse, json, os, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
VAL = os.path.join(HERE, "..", "data", "out", "val.jsonl")
ADAPTERS = os.path.join(HERE, "adapters")
BASE = os.environ.get("BASE_MODEL", "Qwen/Qwen2.5-1.5B-Instruct")
OLLAMA = os.environ.get("OLLAMA", "http://localhost:11434")
JUDGE_MODEL = os.environ.get("JUDGE_MODEL", "ibm/granite4:latest")


def mlx_generate(prompt, adapter=None, max_tokens=256):
    from mlx_lm import load, generate
    model, tok = load(BASE, adapter_path=adapter)
    msgs = [{"role": "user", "content": prompt}]
    text = tok.apply_chat_template(msgs, add_generation_prompt=True, tokenize=False)
    return generate(model, tok, prompt=text, max_tokens=max_tokens, verbose=False)


def judge(q, gold, ans):
    p = (f"질문:{q}\n정답(참고):{gold}\n응답:{ans}\n"
         "응답이 정답과 사실적으로 일치하면 1, 아니면 0. 숫자만.")
    body = {"model": JUDGE_MODEL, "prompt": p, "stream": False,
            "options": {"num_predict": 4}}
    req = urllib.request.Request(f"{OLLAMA}/api/generate",
                                 data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=120) as r:
        out = json.load(r).get("response", "")
    return 1 if "1" in out else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=20)
    ap.add_argument("--judge", action="store_true")
    args = ap.parse_args()

    rows = [json.loads(l) for l in open(VAL, encoding="utf-8") if l.strip()][: args.n]
    sb = sf = 0
    for i, r in enumerate(rows, 1):
        q, gold = r["instruction"], r["output"]
        ab = mlx_generate(q)                       # base
        af = mlx_generate(q, adapter=ADAPTERS)     # finetuned
        if args.judge:
            jb, jf = judge(q, gold, ab), judge(q, gold, af)
            sb += jb; sf += jf
            print(f"[{i}] base={jb} ft={jf}  {q[:40]}")
        else:
            print(f"[{i}] {q[:50]}\n  base: {ab[:80]}\n  ft  : {af[:80]}")
    if args.judge and rows:
        print(f"\n=== LLM-judge 정확도 ===  base {sb}/{len(rows)}  vs  finetuned {sf}/{len(rows)}")


if __name__ == "__main__":
    main()
