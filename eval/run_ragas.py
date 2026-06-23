#!/usr/bin/env python3
"""
RAGAS-lite eval — 외부 ragas 라이브러리 없이 Ollama judge로 4 메트릭 측정.

왜 직접 구현:
    - 프로젝트는 LangChain·OpenAI 없이 로컬 Ollama로 통일. ragas 도입은 의존성 부담이 큼.
    - 메트릭 4개는 LLM judge 프롬프트로 충분히 구현 가능(원논문 Es et al., 2023 동등).

측정 메트릭:
    1) faithfulness          — 답변의 각 진술이 retrieved context에 의해 뒷받침되는 비율
    2) answer_relevance      — 답변이 질문에 얼마나 직접적으로 답하는지 (1~5 → 0~1 정규화)
    3) context_precision     — 검색된 청크 중 실제로 질문과 관련된 비율
    4) context_recall        — expected 답변의 핵심 주장이 retrieved context에 얼마나 포함됐는지

사용:
    python3 eval/run_ragas.py
    JUDGE_MODEL=qwen3:8b API=http://localhost:8080 python3 eval/run_ragas.py
    python3 eval/run_ragas.py --golden eval/golden_vehicle.json
"""
import argparse, json, os, re, statistics, sys, urllib.request

API = os.environ.get("API", "http://localhost:8080")
OLLAMA = os.environ.get("OLLAMA", "http://localhost:11434")
JUDGE_MODEL = os.environ.get("JUDGE_MODEL", "ibm/granite4:latest")
HERE = os.path.dirname(os.path.abspath(__file__))


def ask_rag(question, namespace="vehicle", **filters):
    payload = {"question": question, "namespace": namespace}
    for k, v in filters.items():
        if v is not None: payload[k] = v
    req = urllib.request.Request(
        f"{API}/api/rag/ask",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=180) as r:
        return json.load(r)


def judge_call(prompt, max_tokens=128):
    body = {"model": JUDGE_MODEL, "prompt": prompt, "stream": False, "think": False,
            "options": {"num_predict": max_tokens, "temperature": 0.0}}
    req = urllib.request.Request(
        f"{OLLAMA}/api/generate",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            return json.load(r).get("response", "").strip()
    except Exception as e:
        return f"__ERR__ {e}"


# ── 메트릭 1: faithfulness — 답변의 각 진술이 컨텍스트에 의해 지원되는가? ──
def faithfulness(answer, contexts):
    if not answer or not contexts: return None
    ctx_blob = "\n".join(contexts)[:4000]
    # (a) 답변을 atomic 진술로 분해
    decomp = judge_call(
        "다음 답변을 1문장 단위의 사실 진술(atomic claims)로 분해해 한 줄에 하나씩 출력하라. "
        "넘버링·기호 없이 본문만.\n\n답변:\n" + answer[:1500] + "\n\n진술들:",
        max_tokens=256)
    claims = [l.strip(' -•*0123456789.') for l in decomp.splitlines() if l.strip() and not l.startswith("__ERR__")]
    claims = [c for c in claims if len(c) > 8][:8]
    if not claims: return None
    # (b) 각 진술의 지지 여부 판정
    supported = 0
    for c in claims:
        v = judge_call(
            "다음 컨텍스트가 진술을 직접 지지(O) 또는 지지하지 않음(X)인지 판정하라. 한 글자만(O/X) 답하라.\n\n"
            f"컨텍스트:\n{ctx_blob}\n\n진술: {c}\n판정:",
            max_tokens=4)
        if v and v.upper().startswith("O"): supported += 1
    return supported / len(claims)


# ── 메트릭 2: answer_relevance — 답변이 질문에 직접적으로 답하는가? ──
def answer_relevance(question, answer):
    if not answer: return None
    v = judge_call(
        "다음 답변이 질문에 얼마나 직접적이고 완결적으로 답하는지 1~5로 평가하라. "
        "5=완전, 1=무관/회피. 단 한 자리 숫자만.\n\n"
        f"질문: {question}\n답변: {answer[:1500]}\n점수:",
        max_tokens=4)
    for ch in (v or ""):
        if ch in "12345": return (int(ch) - 1) / 4   # 0..1 정규화
    return None


# ── 메트릭 3: context_precision — 검색된 청크가 실제로 관련 있는가? ──
def context_precision(question, contexts):
    if not contexts: return None
    hits = 0
    for c in contexts[:5]:
        v = judge_call(
            "다음 청크가 질문에 답하는 데 직접 관련 있으면 O, 아니면 X. 한 글자만.\n\n"
            f"질문: {question}\n청크: {c[:800]}\n판정:",
            max_tokens=4)
        if v and v.upper().startswith("O"): hits += 1
    return hits / min(5, len(contexts))


# ── 메트릭 4: context_recall — expected의 핵심 주장이 컨텍스트에 있는가? ──
def context_recall(expected, contexts):
    if not expected or not contexts: return None
    ctx_blob = "\n".join(contexts)[:4000]
    decomp = judge_call(
        "다음 정답을 핵심 주장(atomic claims) 한 줄씩으로 분해하라. 본문만, 기호·번호 없이.\n\n"
        "정답:\n" + expected[:1500] + "\n\n주장들:",
        max_tokens=200)
    claims = [l.strip(' -•*0123456789.') for l in decomp.splitlines() if l.strip() and not l.startswith("__ERR__")]
    claims = [c for c in claims if len(c) > 6][:6]
    if not claims: return None
    found = 0
    for c in claims:
        v = judge_call(
            "다음 컨텍스트에 주장이 (의미상으로) 포함돼 있으면 O, 아니면 X. 한 글자만.\n\n"
            f"컨텍스트:\n{ctx_blob}\n\n주장: {c}\n판정:",
            max_tokens=4)
        if v and v.upper().startswith("O"): found += 1
    return found / len(claims)


def run(cases, filters=None):
    print(f"\n=== RAGAS-lite ({len(cases)} cases, judge={JUDGE_MODEL}) ===\n")
    print(f"{'id':22} {'faith':>7} {'ans-rel':>8} {'ctx-prec':>9} {'ctx-rec':>8}")
    print("-" * 60)
    aggs = {k: [] for k in ("faith", "rel", "prec", "rec")}
    for c in cases:
        try:
            resp = ask_rag(c["question"], c.get("namespace", "vehicle"), **(filters or {}))
            answer = resp.get("answer", "") or ""
            srcs = resp.get("sources", []) or []
            # sources는 Article 객체 — summary가 contexts
            contexts = [s.get("summary", "") for s in srcs if s.get("summary")]
        except Exception as e:
            print(f"{c['id']:22}  ERR: {e}")
            continue
        f1 = faithfulness(answer, contexts)
        f2 = answer_relevance(c["question"], answer)
        f3 = context_precision(c["question"], contexts)
        f4 = context_recall(c.get("expectAnswer"), contexts) if c.get("expectAnswer") else None
        for k, v in zip(("faith", "rel", "prec", "rec"), (f1, f2, f3, f4)):
            if v is not None: aggs[k].append(v)
        def fmt(v): return "-" if v is None else f"{v:.2f}"
        print(f"{c['id']:22} {fmt(f1):>7} {fmt(f2):>8} {fmt(f3):>9} {fmt(f4):>8}")
    print("-" * 60)
    def avg(xs): return f"{statistics.mean(xs):.2f}" if xs else "-"
    print(f"{'avg':22} {avg(aggs['faith']):>7} {avg(aggs['rel']):>8} {avg(aggs['prec']):>9} {avg(aggs['rec']):>8}")
    print()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--golden", default=os.path.join(HERE, "golden_vehicle.json"))
    ap.add_argument("--car", help="메타 필터 — carModel/carCode 부분일치")
    ap.add_argument("--year", type=int)
    ap.add_argument("--lang", help="ko|en")
    ap.add_argument("--powertrain", help="hybrid|electric|phev|fcev|sv")
    args = ap.parse_args()
    if not os.path.exists(args.golden):
        print(f"golden 없음: {args.golden}"); sys.exit(1)
    with open(args.golden, encoding="utf-8") as f:
        cases = json.load(f)
    filters = {k: getattr(args, k) for k in ("car", "year", "lang", "powertrain")}
    filters = {k: v for k, v in filters.items() if v}
    run(cases, filters or None)


if __name__ == "__main__":
    main()
