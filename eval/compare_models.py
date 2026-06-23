#!/usr/bin/env python3
"""
모델 비교 자동화 (v2) — 고정 질문 셋을 여러 chat 모델로 /api/rag/ask 에 N회 던져
한국어 품질·환각거절·정답·지연을 측정한다. 검색(RAG)은 동일, 생성 모델만 바꿔 공정 비교.

채점(질문별 한 가지):
  - expect : 키워드 전부 포함 → 1.0 (정답 단답, 예: 표=35)
  - golden : 골든 키워드 포함 비율 → 부분점수 (예: domain=에어백/경고등/점검)
  - refuse : 거절 표현 있으면 1.0 (환각 안 함이 정답, 예: grounding — 매뉴얼에 없는 리콜 숫자)
누수: 답변에 한자/카나 있으면 1, N회 중 비율 = **누수율**. 요약표는 누수율 오름차순(낮을수록 좋음) 1차 정렬.

사용:
  python3 eval/compare_models.py --n 3 \
    --models qwen2.5:7b-instruct,exaone3.5:7.8b,qwen3:8b,vehicle-qwen2.5-1.5b --out docs/_model_compare.md
"""
import argparse, json, re, statistics, time, urllib.request

QUESTIONS = [
    {"id": "ko_quality", "q": "쏘나타 안전벨트 프리텐셔너 취급 시 주의사항을 한국어로 설명해줘",
     "ns": "vehicle", "golden": ["안전벨트", "프리텐셔너", "주의"]},
    {"id": "table", "q": "오일 드레인 플러그 토크값은?",
     "ns": "vehicle", "title": "test_torque_table.html", "expect": ["35"]},
    {"id": "grounding", "q": "PALISADE 리콜이 몇 건인지 정확한 숫자로 알려줘",
     "ns": "vehicle", "refuse": ["없습니다", "없어요", "없네요", "컨텍스트에 없", "정보가 없", "정보는 없",
                                 "확인할 수 없", "찾을 수 없", "제공되지 않", "나와 있지 않", "포함되어 있지 않"]},
    {"id": "domain", "q": "에어백 경고등이 켜지면 점검 항목은?",
     "ns": "vehicle", "golden": ["에어백", "경고등", "점검"]},
]

CJK  = re.compile(r"[一-鿿]")    # 한자/중국어 누수
KANA = re.compile(r"[぀-ヿ]")    # 일본어 누수


def ask(base, model, q):
    body = {"question": q["q"], "namespace": q.get("ns", "vehicle"), "model": model}
    if q.get("title"):
        body["title"] = q["title"]
    req = urllib.request.Request(base + "/api/rag/ask",
                                 data=json.dumps(body).encode("utf-8"),
                                 headers={"Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=300) as r:
        res = json.load(r)
    return res.get("answer", ""), time.time() - t0


def score(ans, q):
    if q.get("expect"):
        return 1.0 if all(e in ans for e in q["expect"]) else 0.0
    if q.get("refuse"):
        return 1.0 if any(m in ans for m in q["refuse"]) else 0.0   # 거절=정답(환각 안 함)
    if q.get("golden"):
        g = q["golden"]
        return sum(1 for k in g if k in ans) / len(g)               # 부분점수
    return None


def leaked(ans):
    return 1 if (CJK.search(ans) or KANA.search(ans)) else 0


def mean(xs):
    return statistics.mean(xs) if xs else None


def fmt(x, suf=""):
    return "-" if x is None else f"{x:.2f}{suf}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--models", default="qwen2.5:7b-instruct,exaone3.5:7.8b,qwen3:8b,vehicle-qwen2.5-1.5b")
    ap.add_argument("--n", type=int, default=1, help="문항당 반복 수(분산 완화). 누수는 샘플마다 튀어 N>=3 권장")
    ap.add_argument("--out", default="")
    args = ap.parse_args()
    models = [m.strip() for m in args.models.split(",") if m.strip()]

    detail, agg = [], {}
    for m in models:
        agg[m] = {"scores": [], "leaks": [], "lats": []}
        for q in QUESTIONS:
            scs, lks, lts, sample = [], [], [], ""
            for i in range(args.n):
                try:
                    ans, dt = ask(args.base, m, q)
                    s = score(ans, q)
                    if s is not None:
                        scs.append(s)
                    lks.append(leaked(ans)); lts.append(dt)
                    if not sample:
                        sample = ans[:70].replace("\n", " ").replace("|", "/")
                    print(f"[{m:26}/{q['id']:10} #{i+1}] {dt:5.1f}s leak={lks[-1]} score={s}")
                except Exception as e:
                    print(f"[{m:26}/{q['id']:10} #{i+1}] ERROR {e}")
                    if not sample:
                        sample = "ERR: " + str(e)[:60]
            sc, lr, la = mean(scs), mean(lks), mean(lts)
            detail.append((m, q["id"], sc, lr, la, sample))
            if sc is not None: agg[m]["scores"].append(sc)
            if lr is not None: agg[m]["leaks"].append(lr)
            if la is not None: agg[m]["lats"].append(la)

    out = [f"### 모델 비교 (자동, n={args.n})\n",
           "| 모델 | 질문 | 점수 | 누수율 | 평균지연 | 답변(앞 70자) |",
           "|---|---|---|---|---|---|"]
    for m, qid, sc, lr, la, sample in detail:
        out.append(f"| {m} | {qid} | {fmt(sc)} | {fmt(lr)} | {'-' if la is None else f'{la:.1f}s'} | {sample} |")

    # 요약 — 누수율 오름차순(낮을수록 좋음) 1차, 지연 2차
    rows = []
    for m, a in agg.items():
        rows.append((m, mean(a["leaks"]), mean(a["scores"]), mean(a["lats"])))
    rows.sort(key=lambda r: (9 if r[1] is None else r[1], 9 if r[3] is None else r[3]))
    out += ["\n**요약** (누수율↓·점수↑·지연↓ 이 좋음 — 누수율 1차 정렬)\n",
            "| 순위 | 모델 | 누수율 | 평균점수 | 평균지연 |", "|---|---|---|---|---|"]
    for i, (m, lr, sc, la) in enumerate(rows, 1):
        out.append(f"| {i} | {m} | {fmt(lr)} | {fmt(sc)} | {'-' if la is None else f'{la:.1f}s'} |")

    md = "\n".join(out)
    print("\n" + md)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(md + "\n")
        print(f"\n[saved] {args.out}")


if __name__ == "__main__":
    main()
