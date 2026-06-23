#!/usr/bin/env python3
"""
Ingest 중복 진단.

두 가지 케이스를 한 번에 잡는다:

  (A) 같은 파일명으로 여러 번 ingest — title 베이스('#N' 접미사 제거) 그룹핑.
      카탈로그 dedup 이전에 들어온 데이터는 같은 baseTitle이 여러 ingestedAt에
      걸쳐 청크 세트가 중복 적재되었을 수 있다.

  (B) 다른 파일명 / 같은 내용 — summary(저장본=임베딩본) 텍스트의 SHA-1
      해시로 그룹핑. title이 달라도 동일 청크가 여러 곳에 있으면 중복.

대상:
  - data/articles.json       (hot, JSON append)
  - data/articles.parquet    (legacy cold, 단일 파일)
  - data/articles/*.parquet  (신 파티션 — 있으면 함께)

사용:
  python3 scripts/scan_duplicates.py [--top 20]

출력: 두 표(A, B) — 상위 N개 중복 그룹과 총 중복 청크 수.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"

CHUNK_SUFFIX = re.compile(r" #\d+$")


def base_title(t: str) -> str:
    return CHUNK_SUFFIX.sub("", t or "")


def content_hash(s: str) -> str:
    return hashlib.sha1((s or "").strip().encode("utf-8", "ignore")).hexdigest()[:12]


def load_json(p: Path):
    if not p.exists() or p.stat().st_size == 0:
        return []
    text = p.read_text(encoding="utf-8")
    # 다중 라인 JSON / append 형식 둘 다 허용
    try:
        v = json.loads(text)
        return v if isinstance(v, list) else [v]
    except json.JSONDecodeError:
        out = []
        for ln in text.splitlines():
            ln = ln.strip()
            if not ln:
                continue
            try:
                out.append(json.loads(ln))
            except json.JSONDecodeError:
                pass
        return out


def load_parquet(p: Path):
    try:
        import pyarrow.parquet as pq  # type: ignore
    except ImportError:
        print(f"[skip] {p.name}: pyarrow not installed (pip install pyarrow)", file=sys.stderr)
        return []
    if not p.exists() or p.stat().st_size < 8:
        return []
    table = pq.read_table(p)
    cols = {n: table.column(n).to_pylist() for n in ("id", "namespace", "title", "summary") if n in table.column_names}
    n = len(cols.get("id", []))
    out = []
    for i in range(n):
        out.append({
            "id": cols.get("id", [None] * n)[i],
            "namespace": cols.get("namespace", ["default"] * n)[i],
            "title": cols.get("title", [""] * n)[i],
            "summary": cols.get("summary", [""] * n)[i],
        })
    return out


def collect():
    articles = []
    articles += [(a, "json") for a in load_json(DATA / "articles.json")]
    articles += [(a, "legacy.parquet") for a in load_parquet(DATA / "articles.parquet")]
    part_dir = DATA / "articles"
    if part_dir.is_dir():
        for f in sorted(part_dir.glob("*.parquet")):
            articles += [(a, f"partition/{f.name}") for a in load_parquet(f)]
    return articles


def report_a(articles, top: int):
    groups: dict[tuple[str, str], list] = defaultdict(list)
    for a, src in articles:
        ns = a.get("namespace") or "default"
        bt = base_title(a.get("title") or "")
        groups[(ns, bt)].append((a, src))

    multi = [(k, v) for k, v in groups.items() if len({a.get("title") for a, _ in v}) >= len(v) and len(v) >= 1]
    # baseTitle별 청크 수와 중복 의심: 같은 baseTitle인데 #N이 1..K를 두 번 이상 본 케이스
    suspect = []
    for (ns, bt), rows in groups.items():
        chunk_indices = Counter()
        for a, _ in rows:
            t = a.get("title") or ""
            m = re.search(r" #(\d+)$", t)
            if m:
                chunk_indices[int(m.group(1))] += 1
        dup_chunks = sum(c for c in chunk_indices.values() if c > 1)
        if dup_chunks > 0:
            suspect.append((ns, bt, len(rows), dup_chunks, chunk_indices))

    suspect.sort(key=lambda r: -r[3])
    print("\n== (A) 같은 baseTitle, 같은 청크 인덱스가 중복 — 같은 파일을 두 번 이상 ingest ==")
    if not suspect:
        print("  no duplicates found.")
        return
    print(f"  {'ns':<10} {'baseTitle':<60} {'rows':>6} {'dup_chunks':>10}")
    for ns, bt, rows, dup, _idx in suspect[:top]:
        print(f"  {ns:<10} {bt[:60]:<60} {rows:>6} {dup:>10}")
    total_dup = sum(s[3] for s in suspect)
    print(f"  ---\n  total duplicate-chunk count: {total_dup}")
    print(f"  affected documents: {len(suspect)}")


def report_b(articles, top: int):
    by_hash: dict[str, list] = defaultdict(list)
    for a, src in articles:
        h = content_hash(a.get("summary") or "")
        if not h:
            continue
        by_hash[h].append((a, src))

    dups = [(h, rows) for h, rows in by_hash.items() if len(rows) > 1]
    dups.sort(key=lambda x: -len(x[1]))
    print("\n== (B) 다른 title, 같은 content — content-hash 충돌 ==")
    if not dups:
        print("  no content-hash collisions.")
        return
    print(f"  {'hash':<14} {'count':>6}  examples (title × ns)")
    for h, rows in dups[:top]:
        ex = ", ".join(
            sorted({f"{(a.get('title') or '')[:40]}@{a.get('namespace') or 'default'}" for a, _ in rows[:3]})
        )
        print(f"  {h:<14} {len(rows):>6}  {ex}")
    total_extra = sum(len(rows) - 1 for _, rows in dups)
    print(f"  ---\n  redundant chunks (count - 1 per group): {total_extra}")
    print(f"  affected groups: {len(dups)}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--top", type=int, default=20)
    args = ap.parse_args()

    articles = collect()
    print(f"loaded {len(articles)} article rows from data/ (hot json + cold parquet + partitions)")

    report_a(articles, args.top)
    report_b(articles, args.top)


if __name__ == "__main__":
    main()
