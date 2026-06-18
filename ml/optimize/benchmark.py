#!/usr/bin/env python3
"""
P3 — 추론 벤치마크 (양자화 전후/모델별). Ollama 대상.

측정: TTFT(첫 토큰 지연), tok/s, 총 지연, (옵션) 응답 길이.
온디바이스 스토리의 핵심 증거지표. 결과는 CSV로도 저장.

사용:
  python3 ml/optimize/benchmark.py --models "Qwen/Qwen2.5-1.5B-Instruct,vehicle-qwen2.5-1.5b" --runs 5
  (Ollama에 등록된 모델 이름 사용. base는 ollama pull 또는 등록 필요)
"""
import argparse, csv, json, os, time, urllib.request, statistics as st

OLLAMA = os.environ.get("OLLAMA", "http://localhost:11434")
PROMPTS = [
    "P0420 고장 코드의 의미와 대표 원인은?",
    "TPMS 경고등이 켜지면 어떻게 해야 해?",
    "전기차 SOC와 SOH의 차이를 설명해줘.",
]


def stream_once(model, prompt):
    body = {"model": model, "prompt": prompt, "stream": True,
            "options": {"num_predict": 200}}
    req = urllib.request.Request(f"{OLLAMA}/api/generate",
                                 data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    t0 = time.perf_counter(); ttft = None; ntok = 0
    with urllib.request.urlopen(req, timeout=300) as r:
        for line in r:
            line = line.strip()
            if not line:
                continue
            d = json.loads(line)
            if d.get("response"):
                if ttft is None:
                    ttft = time.perf_counter() - t0
                ntok += 1
            if d.get("done"):
                break
    total = time.perf_counter() - t0
    tps = ntok / total if total > 0 else 0
    return ttft or total, tps, total, ntok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", required=True, help="콤마구분 Ollama 모델명")
    ap.add_argument("--runs", type=int, default=5)
    ap.add_argument("--csv", default=os.path.join(os.path.dirname(__file__), "bench.csv"))
    args = ap.parse_args()

    rows = []
    for model in [m.strip() for m in args.models.split(",") if m.strip()]:
        ttfts, tpss, totals = [], [], []
        for i in range(args.runs):
            p = PROMPTS[i % len(PROMPTS)]
            ttft, tps, total, ntok = stream_once(model, p)
            ttfts.append(ttft); tpss.append(tps); totals.append(total)
        row = {"model": model,
               "ttft_s": round(st.median(ttfts), 3),
               "tok_per_s": round(st.median(tpss), 1),
               "total_s": round(st.median(totals), 3)}
        rows.append(row)
        print(f"{model:40s}  TTFT={row['ttft_s']}s  {row['tok_per_s']} tok/s  total={row['total_s']}s")

    with open(args.csv, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["model", "ttft_s", "tok_per_s", "total_s"])
        w.writeheader(); w.writerows(rows)
    print(f"\n[csv] {args.csv}")


if __name__ == "__main__":
    main()
