#!/usr/bin/env python3
"""
P2 — 코퍼스 → instruction 데이터셋(JSONL) 생성.

소스: 앱의 vehicle 네임스페이스 청크(GET /api/data/articles?namespace=vehicle).
방법: 각 청크에서 Ollama로 Q&A(instruction) 쌍을 합성(distillation).
출력: ml/data/out/train.jsonl, val.jsonl  ({"instruction","input","output"})

사용:
  python3 ml/data/build_dataset.py --n-per-chunk 2 --max-chunks 200
  API=http://localhost:8080 OLLAMA=http://localhost:11434 python3 ml/data/build_dataset.py
"""
import argparse, json, os, re, urllib.request, random

API = os.environ.get("API", "http://localhost:8080")
OLLAMA = os.environ.get("OLLAMA", "http://localhost:11434")
GEN_MODEL = os.environ.get("GEN_MODEL", "ibm/granite4:latest")
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")


def http_json(url, payload=None, method="GET", timeout=180):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def fetch_chunks(ns):
    arts = http_json(f"{API}/api/data/articles?namespace={ns}")
    out = []
    for a in arts:
        txt = (a.get("summary") or "").strip()
        if len(txt) >= 80:                      # 너무 짧은 청크 제외
            out.append({"title": a.get("title", ""), "text": txt})
    return out


def gen(prompt):
    body = {"model": GEN_MODEL, "prompt": prompt, "stream": False,
            "options": {"num_predict": 512, "temperature": 0.2}}  # 낮은 temp = 환각↓
    r = http_json(f"{OLLAMA}/api/generate", body, method="POST")
    return r.get("response", "")


PROMPT = """너는 자동차 정비 매뉴얼 전문 한국어 어시스턴트다.
아래 영어 발췌를 바탕으로 한국어 Q&A {n}쌍을 만들어라.

규칙(엄수):
- **한국어로만 작성. 중국어·한자(漢字) 절대 금지** (한글과 필요한 영어 약어만).
- 답변은 발췌에 실제로 있는 내용만. 발췌에 없으면 만들지 마라(환각 금지).
- 고유명사 정확히: Hyundai→현대, ignition→점화/시동, 영어 약어/코드(P0420 등)는 원문 그대로 둔다.
- 어색한 음역 금지(예: "하다모"·"이등신" 같은 단어 절대 쓰지 마라). 자연스러운 한국어로.
- 발췌가 목차/표지/페이지번호처럼 Q&A로 부적합하면 빈 배열 [] 만 출력.
- 출력은 JSON 배열만: [{{"q":"질문","a":"답변"}}]

[발췌]
{chunk}
"""


CJK = re.compile(r"[一-鿿]")   # 중국어/한자 — 한국어 답변엔 불필요, 새어들면 제거


def has_chinese(text):
    return bool(CJK.search(text or ""))


def parse_pairs(s):
    m = re.search(r"\[.*\]", s, re.S)
    if not m:
        return []
    try:
        arr = json.loads(m.group(0))
        return [(d["q"], d["a"]) for d in arr if d.get("q") and d.get("a")]
    except Exception:
        return []


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--namespace", default="vehicle")
    ap.add_argument("--n-per-chunk", type=int, default=2)
    ap.add_argument("--max-chunks", type=int, default=200)
    ap.add_argument("--val-ratio", type=float, default=0.1)
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    os.makedirs(OUT, exist_ok=True)
    chunks = fetch_chunks(args.namespace)[: args.max_chunks]
    print(f"[build] {len(chunks)} chunks from ns={args.namespace}")

    rows = []
    for i, c in enumerate(chunks, 1):
        pairs = parse_pairs(gen(PROMPT.format(n=args.n_per_chunk, chunk=c["text"][:1500])))
        for q, a in pairs:
            if has_chinese(q) or has_chinese(a):
                continue   # 중국어/한자 누출 행 제거 (qwen 다국어 누출 방지)
            rows.append({"instruction": q, "input": "", "output": a,
                         "meta": {"title": c["title"]}})
        if i % 10 == 0:
            print(f"  {i}/{len(chunks)} chunks → {len(rows)} pairs")

    random.Random(args.seed).shuffle(rows)
    n_val = max(1, int(len(rows) * args.val_ratio)) if rows else 0
    val, train = rows[:n_val], rows[n_val:]

    for name, data in (("train", train), ("val", val)):
        p = os.path.join(OUT, f"{name}.jsonl")
        with open(p, "w", encoding="utf-8") as f:
            for r in data:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        print(f"[write] {p}  ({len(data)} rows)")


if __name__ == "__main__":
    main()
