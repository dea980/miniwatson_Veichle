#!/usr/bin/env python3
"""
data/vehicle/manuals/ 에 이미 있는 PDF를 **선별 적재**(KB 인제스트).

배경: 폴더에 현대 전 라인업 매뉴얼이 다수(수백 권) 있다. 전부 적재하면 임베딩 수 시간 +
      벡터스토어 폭증이므로, 모델/연식/언어로 골라 넣는다. 기본은 dry-run(미리보기).

규칙 파일명: hyundai_<year>_<model>[_variant]_<projCode>_owners_<KR|EN>.pdf

사용:
    python scripts/ingest_existing_manuals.py                       # 미리보기(아무것도 안 넣음)
    python scripts/ingest_existing_manuals.py --models ioniq5,nexo,palisade --apply
    python scripts/ingest_existing_manuals.py --years 2024,2025,2026 --lang KR --apply
    python scripts/ingest_existing_manuals.py --models tucson --years 2025 --apply

백엔드(8080) 실행 중이어야 함. namespace=vehicle.
"""
import argparse
import glob
import os
import re
import sys
import time
import urllib.request
import uuid

MANUALS_DIR = "data/vehicle/manuals"
INGEST_URL = "http://localhost:8080/api/data/ingest-file"


def ingest(path, namespace="vehicle"):
    boundary = "----mw" + uuid.uuid4().hex
    with open(path, "rb") as f:
        content = f.read()
    fn = os.path.basename(path)
    pre = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"namespace\"\r\n\r\n{namespace}\r\n"
           f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{fn}\"\r\n"
           f"Content-Type: application/pdf\r\n\r\n").encode("utf-8")
    body = pre + content + f"\r\n--{boundary}--\r\n".encode("utf-8")
    req = urllib.request.Request(INGEST_URL, data=body, method="POST",
                                 headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    with urllib.request.urlopen(req, timeout=900) as r:
        return r.status


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default=None, help="콤마구분 모델 부분일치(파일명) 예: ioniq5,nexo,palisade")
    ap.add_argument("--years", default=None, help="콤마구분 연식 예: 2024,2025,2026")
    ap.add_argument("--lang", default=None, choices=["KR", "EN"], help="언어 필터")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--apply", action="store_true", help="실제 인제스트(미지정 시 dry-run)")
    args = ap.parse_args()

    files = sorted(glob.glob(os.path.join(MANUALS_DIR, "hyundai_*_owners_*.pdf")))
    models = [m.strip().lower() for m in args.models.split(",")] if args.models else None
    years = [y.strip() for y in args.years.split(",")] if args.years else None

    sel = []
    for f in files:
        b = os.path.basename(f).lower()
        if args.lang and not b.endswith(f"_owners_{args.lang.lower()}.pdf"):
            continue
        if years and not any(("_" + y + "_") in b for y in years):
            continue
        if models and not any(m in b for m in models):
            continue
        sel.append(f)
    if args.limit > 0:
        sel = sel[:args.limit]

    print(f"[ingest] 매칭 {len(sel)}권 / 전체 {len(files)}권" + ("" if args.apply else "  (dry-run — --apply 로 실제 적재)"))
    for f in sel:
        print(("  → " if args.apply else "  · ") + os.path.basename(f))
    if not args.apply:
        # 대략 비용 가늠(권당 ~350청크 가정)
        print(f"[ingest] 예상 청크 ≈ {len(sel)*350:,} (권당 ~350 가정). 임베딩 부하 큼 — 핵심부터 권장.")
        return

    ok = 0
    for f in sel:
        try:
            st = ingest(f); ok += 1
            print(f"  ✓ {os.path.basename(f)} [{st}]")
        except Exception as e:
            print(f"  ! {os.path.basename(f)} 실패(백엔드 확인): {e}")
        time.sleep(0.3)
    print(f"[ingest] 완료: {ok}/{len(sel)} 적재 (namespace=vehicle)")


if __name__ == "__main__":
    main()
