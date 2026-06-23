#!/usr/bin/env python3
"""
NHTSA 불만(complaints) full-summary 재수집 → 로컬 CSV 재적재.

배경: 기존 data/vehicle/complaints/hyundai_complaints_nhtsa.csv 의 summary가
      수집 시점에 500자로 잘려 있다(1429건 전부 max 500자). 케이스 상세에서 접수 내용이
      문장 중간에서 끊기는 원인. NHTSA complaintsByVehicle API는 전체 summary를 주므로
      여기서 다시 받아 CSV를 재적재한다.

사용:
    python scripts/refetch_nhtsa_complaints.py              # 기존 CSV 덮어쓰기(백업 자동)
    python scripts/refetch_nhtsa_complaints.py --src <csv> --out <csv>

이후: 백엔드에 POST /api/analytics/refresh (또는 재시작) 하면 새 CSV가 재등록된다.

주의: 이 스크립트는 사용자 머신에서 직접 NHTSA 공개 API(api.nhtsa.gov)를 호출한다.
"""
import argparse
import csv
import datetime
import json
import os
import shutil
import ssl
import sys
import time
import urllib.parse
import urllib.request

API = "https://api.nhtsa.gov/complaints/complaintsByVehicle"
MODELS_API = "https://api.nhtsa.gov/products/vehicle/models"


def make_ssl_context():
    """macOS 파이썬은 루트 인증서가 없어 SSL 검증이 실패하는 경우가 많다.
    certifi가 있으면 그 번들로 검증하고, 없으면 미검증 컨텍스트로 폴백(공개·읽기전용 정부 API)."""
    try:
        import certifi
        return ssl.create_default_context(cafile=certifi.where())
    except Exception:
        print("[refetch] certifi 없음 — SSL 검증 생략(공개 API, 읽기전용)")
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        return ctx


SSL_CTX = make_ssl_context()
# 앱(DuckDB)이 기대하는 컬럼 순서/이름 — 변경 금지
COLS = ["odiNumber", "dateComplaintFiled", "make", "model", "modelYear",
        "components", "crash", "fire", "numberOfInjuries", "numberOfDeaths", "summary"]


def _get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "miniwatson-refetch/1.0"})
    with urllib.request.urlopen(req, timeout=60, context=SSL_CTX) as r:
        return json.load(r)


def fetch(make, model, year):
    q = urllib.parse.urlencode({"make": make, "model": model, "modelYear": year})
    return _get(f"{API}?{q}").get("results", [])


def list_models(make, year):
    """NHTSA가 해당 연식·메이커에 대해 불만(issueType=c) 데이터를 가진 모델 목록.
    아이오닉/하이브리드/EV 등 모든 변형을 추측 없이 정확히 가져온다."""
    q = urllib.parse.urlencode({"modelYear": year, "make": make, "issueType": "c"})
    res = _get(f"{MODELS_API}?{q}").get("results", [])
    return sorted({(r.get("model") or "").strip().upper() for r in res if r.get("model")})


def fmt_date(v):
    """API의 dateComplaintFiled(보통 YYYYMMDD) → 기존 CSV 형식 MM/DD/YYYY로 정규화."""
    if not v:
        return ""
    s = str(v)
    if len(s) == 8 and s.isdigit():
        return f"{s[4:6]}/{s[6:8]}/{s[0:4]}"
    try:
        d = datetime.datetime.fromisoformat(s.replace("Z", ""))
        return d.strftime("%m/%d/%Y")
    except Exception:
        return s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default="data/vehicle/complaints/hyundai_complaints_nhtsa.csv")
    ap.add_argument("--out", default=None, help="기본: --src 덮어쓰기")
    ap.add_argument("--make", default="HYUNDAI")
    ap.add_argument("--models", default=None,
                    help="콤마구분 모델 목록(미지정 시 NHTSA에서 연식별 전체 모델 자동 조회 — 전기/하이브리드 포함)")
    ap.add_argument("--years", default="2020-2026", help="연식 범위 'YYYY-YYYY' 또는 콤마구분")
    args = ap.parse_args()
    src, out = args.src, (args.out or args.src)

    if not os.path.exists(src):
        print(f"[refetch] 원본 없음: {src}")
        sys.exit(1)

    # 연식: 'YYYY-YYYY' 범위 또는 콤마구분
    if "-" in args.years and "," not in args.years:
        a, b = args.years.split("-"); years = [str(y) for y in range(int(a), int(b) + 1)]
    else:
        years = [y.strip() for y in args.years.split(",") if y.strip()]

    # 조합 구성: --models 주면 그 모델×연식, 아니면 NHTSA에서 연식별 전체 모델 자동 조회(EV/하이브리드 포함)
    combos = []
    if args.models:
        models = [m.strip().upper() for m in args.models.split(",") if m.strip()]
        combos = [(args.make, m, y) for m in models for y in years]
        print(f"[refetch] (지정) {len(models)} models × {len(years)} years = {len(combos)} combos")
    else:
        print(f"[refetch] NHTSA에서 {args.make} 모델 자동 조회 (연식 {years[0]}~{years[-1]})…")
        allm = set()
        for y in years:
            try:
                ms = list_models(args.make, y)
            except Exception as e:
                print(f"  ! {y} 모델 조회 실패: {e}")
                ms = []
            allm |= set(ms)
            combos += [(args.make, m, y) for m in ms]
            print(f"  {y}: {len(ms)}종 — {', '.join(ms) if ms else '(없음)'}")
            time.sleep(0.3)
        print(f"[refetch] 자동 조회 결과: 모델 {len(allm)}종, 총 {len(combos)} combos")

    rows, seen = [], set()
    for make, model, year in combos:
        try:
            res = fetch(make, model, year)
            print(f"  {make} {model} {year}: {len(res)} complaints")
        except Exception as e:
            print(f"  ! {make} {model} {year} 실패: {e}")
            continue
        for it in res:
            odi = str(it.get("odiNumber", "")).strip()
            if not odi or odi in seen:
                continue
            seen.add(odi)
            rows.append({
                "odiNumber": odi,
                "dateComplaintFiled": fmt_date(it.get("dateComplaintFiled")),
                "make": make, "model": model, "modelYear": year,
                "components": it.get("components", "") or "",
                "crash": it.get("crash", False),
                "fire": it.get("fire", False),
                "numberOfInjuries": it.get("numberOfInjuries", 0) or 0,
                "numberOfDeaths": it.get("numberOfDeaths", 0) or 0,
                "summary": (it.get("summary", "") or "").replace("\r", " ").replace("\n", " ").strip(),
            })
        time.sleep(0.5)  # API 예의

    if not rows:
        print("[refetch] 수신 0건 — 중단(원본 보존)")
        sys.exit(1)

    # 백업 후 기록
    bak = src + ".bak." + datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    shutil.copy(src, bak)
    print(f"[refetch] 백업: {bak}")

    with open(out, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=COLS)
        w.writeheader()
        for r in rows:
            w.writerow(r)

    lens = [len(r["summary"]) for r in rows]
    capped = sum(1 for x in lens if x == 500)
    print(f"[refetch] {len(rows)}건 적재 → {out}")
    print(f"[refetch] summary 길이: max={max(lens)}, 평균={sum(lens)//len(lens)}, 정확히500자={capped}")
    print("[refetch] 완료. 백엔드에 POST /api/analytics/refresh 또는 재시작하세요.")


if __name__ == "__main__":
    main()
