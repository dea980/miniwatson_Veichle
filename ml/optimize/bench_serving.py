#!/usr/bin/env python3
"""
P3 — 서빙 최적화 벤치 (prefix cache · 연속 배칭). OpenAI 호환 엔드포인트 대상.

benchmark.py(단일 요청 TTFT/tok-s, Ollama)와 역할이 다르다. 이건 **서빙 계층**의 두 축을 측정한다:
  1) Prefix caching  — 긴 공유 프리픽스(고정 system+컨텍스트)의 KV 재사용으로 TTFT가 줄어드는가.
  2) 연속 배칭       — 동시 요청 수를 올릴 때 처리량(req/s)·지연(p50/p95)이 어떻게 변하는가.

vLLM(:8000)과 Ollama(:11434, /v1 OpenAI 호환) 둘 다에 같은 방식으로 쏴 대조할 수 있다. 표준 라이브러리만 사용.

사용:
  # vLLM
  python3 ml/optimize/bench_serving.py --base http://localhost:8000 --model Qwen/Qwen2.5-7B-Instruct
  # Ollama (같은 프롬프트로 대조)
  python3 ml/optimize/bench_serving.py --base http://localhost:11434 --model qwen2.5:7b-instruct
  # 옵션: --concurrency 1,2,4,8  --runs 6  --csv out.csv
"""
import argparse, csv, json, os, ssl, time, urllib.request
import statistics as st
from concurrent.futures import ThreadPoolExecutor

CTX = ("[차량 정비 매뉴얼 컨텍스트] "  # 긴 공유 프리픽스 — prefix cache가 재사용할 부분(고정)
       "다음은 오너스 매뉴얼 발췌다. 경고등, 정기 점검 주기, 안전 주의사항, 제원표가 포함된다. ") * 40
SYSTEM = "너는 자동차 정비 어시스턴트다. 한국어로 간결히 답한다."
QUESTIONS = [
    "안전벨트 경고등은 언제 켜지나요?",
    "정기 점검 주기를 알려줘.",
    "TPMS 경고등이 켜지면?",
    "브레이크 소음의 대표 원인은?",
]
_CTX = ssl.create_default_context()


def _post(base, path, body, stream=False, timeout=300):
    req = urllib.request.Request(base + path, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    return urllib.request.urlopen(req, timeout=timeout, context=_CTX if base.startswith("https") else None)


def msgs(prefix, question):
    # 공유 프리픽스(system+CTX) 뒤에 짧은 질문만 바뀐다 → prefix cache가 CTX의 KV를 재사용할 수 있음.
    return [{"role": "system", "content": SYSTEM},
            {"role": "user", "content": prefix + "\n\n질문: " + question}]


def ttft_once(base, model, prefix, question, max_tokens=64):
    """스트리밍으로 첫 토큰 지연(TTFT) 측정."""
    body = {"model": model, "messages": msgs(prefix, question), "stream": True,
            "max_tokens": max_tokens, "temperature": 0}
    t0 = time.perf_counter()
    with _post(base, "/v1/chat/completions", body, stream=True) as r:
        for raw in r:
            line = raw.decode("utf-8", "ignore").strip()
            if not line or not line.startswith("data:"):
                continue
            data = line[5:].strip()
            if data == "[DONE]":
                break
            try:
                delta = json.loads(data)["choices"][0].get("delta", {})
            except Exception:
                continue
            if delta.get("content"):
                return time.perf_counter() - t0
    return time.perf_counter() - t0


def latency_once(base, model, question, max_tokens=128):
    """비스트리밍 — 전체 완성 지연 측정(동시성 테스트용)."""
    body = {"model": model, "messages": msgs(CTX, question), "stream": False,
            "max_tokens": max_tokens, "temperature": 0}
    t0 = time.perf_counter()
    with _post(base, "/v1/chat/completions", body) as r:
        r.read()
    return time.perf_counter() - t0


def pct(xs, p):
    if not xs:
        return 0.0
    xs = sorted(xs)
    k = min(len(xs) - 1, int(round((p / 100) * (len(xs) - 1))))
    return xs[k]


def bench_prefix(base, model, runs):
    """warm(같은 긴 프리픽스 반복) vs cold(매번 다른 프리픽스) TTFT 비교."""
    ttft_once(base, model, CTX, "웜업")                       # 프리픽스 KV 채우기(warm-up)
    warm = [ttft_once(base, model, CTX, QUESTIONS[i % len(QUESTIONS)]) for i in range(runs)]
    cold = [ttft_once(base, model, CTX + f" [nonce-{i}-{time.time_ns()}]", QUESTIONS[i % len(QUESTIONS)])
            for i in range(runs)]                             # nonce로 프리픽스를 매번 다르게 → 캐시 미스 유도
    return st.mean(warm), st.mean(cold)


def bench_concurrency(base, model, levels, runs):
    rows = []
    for c in levels:
        lats = []
        t0 = time.perf_counter()
        with ThreadPoolExecutor(max_workers=c) as ex:
            futs = [ex.submit(latency_once, base, model, QUESTIONS[i % len(QUESTIONS)]) for i in range(c * runs)]
            for f in futs:
                lats.append(f.result())
        wall = time.perf_counter() - t0
        rows.append({"concurrency": c, "req": len(lats), "throughput_rps": round(len(lats) / wall, 2),
                     "p50_s": round(pct(lats, 50), 2), "p95_s": round(pct(lats, 95), 2)})
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=os.environ.get("BASE", "http://localhost:8000"))
    ap.add_argument("--model", required=True)
    ap.add_argument("--concurrency", default="1,2,4,8")
    ap.add_argument("--runs", type=int, default=4, help="동시성 레벨당 (c×runs) 요청 / prefix TTFT 반복 수")
    ap.add_argument("--csv", default="")
    a = ap.parse_args()
    levels = [int(x) for x in a.concurrency.split(",") if x.strip()]

    print(f"\n대상: {a.base}  모델: {a.model}\n")

    print("① Prefix caching (TTFT, 낮을수록 좋음)")
    warm, cold = bench_prefix(a.base, a.model, a.runs)
    saved = (1 - warm / cold) * 100 if cold else 0
    print(f"  warm(프리픽스 재사용) {warm*1000:7.1f} ms  |  cold(매번 새 프리픽스) {cold*1000:7.1f} ms"
          f"  |  절감 {saved:5.1f}%\n")

    print("② 연속 배칭 (동시성별 처리량·지연)")
    print(f"  {'동시성':>5} {'요청':>5} {'처리량(req/s)':>14} {'p50(s)':>9} {'p95(s)':>9}")
    rows = bench_concurrency(a.base, a.model, levels, a.runs)
    for r in rows:
        print(f"  {r['concurrency']:>5} {r['req']:>5} {r['throughput_rps']:>14} {r['p50_s']:>9} {r['p95_s']:>9}")

    if a.csv:
        with open(a.csv, "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(["metric", "concurrency", "req", "throughput_rps", "p50_s", "p95_s", "warm_ttft_ms", "cold_ttft_ms"])
            w.writerow(["prefix", "", "", "", "", "", round(warm*1000, 1), round(cold*1000, 1)])
            for r in rows:
                w.writerow(["concurrency", r["concurrency"], r["req"], r["throughput_rps"], r["p50_s"], r["p95_s"], "", ""])
        print(f"\nCSV 저장: {a.csv}")
    print()


if __name__ == "__main__":
    main()
