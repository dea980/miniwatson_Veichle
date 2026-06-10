#!/usr/bin/env python3
"""
MiniWatson retrieval eval harness.

Runs each question in golden.json against /api/rag/ask and checks whether the
returned sources contain the expected document/keywords. Reports recall (hit rate)
and per-question hit/miss.

Usage:
    python3 eval/run_eval.py
    API=http://localhost:8080 python3 eval/run_eval.py

To compare configs: change application.yaml (chunking.strategy / rerank.strategy /
retrieval.hybrid.enabled), restart, re-run. Tag each run with LABEL=...:
    LABEL="recursive+cross+hybrid" python3 eval/run_eval.py
"""
import json, os, sys, urllib.request, urllib.error

API = os.environ.get("API", "http://localhost:8080")
LABEL = os.environ.get("LABEL", "")
HERE = os.path.dirname(os.path.abspath(__file__))

CONFIGS = [
    {"rerank": "none", "hybrid": False},
    {"rerank": "none", "hybrid": True},
    {"rerank": "llm",  "hybrid": True},
    {"rerank": "mmr",  "hybrid": True},
]

def ask(question, namespace, rerank=None, hybrid=None):
    payload = {"question": question, "namespace": namespace}
    if rerank is not None:
        payload["rerank"] = rerank      # EVAL-ONLY override
    if hybrid is not None:
        payload["hybrid"] = hybrid      # EVAL-ONLY override
    req = urllib.request.Request(
        f"{API}/api/rag/ask",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=180) as r:
        return json.load(r)


def hit(case, resp):
    """A case is a hit if any source matches the expectation."""
    sources = resp.get("sources", []) or []
    blob = " ".join(
        (s.get("title", "") + " " + s.get("summary", "")).lower() for s in sources
    )
    if "expectTitleContains" in case:
        if case["expectTitleContains"].lower() not in blob:
            return False
    if "expectKeywords" in case:
        # all keywords must appear somewhere in the retrieved sources
        for kw in case["expectKeywords"]:
            if kw.lower() not in blob:
                return False
    return True


def main():
    with open(os.path.join(HERE, "golden.json")) as f:
        cases = json.load(f)
    n = len(cases)

    print(f"\n=== MiniWatson retrieval eval ({n} cases) ===\n")
    print(f"{'config':32} {'recall':>10}   misses")
    print("-" * 70)
    for cfg in CONFIGS:
        hits, misses = 0, []
        for c in cases:
            try:
                resp = ask(c["question"], c.get("namespace", "default"),
                           cfg["rerank"], cfg["hybrid"])
                ok = hit(c, resp)
            except Exception:
                ok = False
            if ok:
                hits += 1
            else:
                misses.append(c["id"])
        label = f"rerank={cfg['rerank']}, hybrid={cfg['hybrid']}"
        pct = f"{hits}/{n} ({hits/n:.0%})"
        print(f"{label:32} {pct:>10}   {', '.join(misses) if misses else '-'}")
    print()

if __name__ == "__main__":
    main()
