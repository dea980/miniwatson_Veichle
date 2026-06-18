#!/usr/bin/env python3
"""
NHTSA 불만/민원(complaints) 원본 수집기 (API 키 불필요, 공개).
recalls와 같은 패턴 — Agent의 두 번째 정형 테이블(테이블 선택 데모용).

  - 원본 JSON: data/vehicle/complaints/raw/{model}_{year}.json
  - 가공 CSV : data/vehicle/complaints/hyundai_complaints_nhtsa.csv

API: https://api.nhtsa.gov/complaints/complaintsByVehicle?make=&model=&modelYear=

사용:
  python3 ml/data/fetch_complaints.py --insecure          # 맥 SSL 우회
  python3 ml/data/fetch_complaints.py --models "elantra,sonata,tucson" --years 2020-2022
"""
import argparse, csv, json, os, ssl, time, urllib.parse, urllib.request

API = "https://api.nhtsa.gov/complaints/complaintsByVehicle"
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT_DIR = os.path.join(ROOT, "data", "vehicle", "complaints")
RAW_DIR = os.path.join(OUT_DIR, "raw")

# 평탄화 필드 (make/model/modelYear는 조회 파라미터로 주입)
FIELDS = ["odiNumber", "dateComplaintFiled", "make", "model", "modelYear",
          "components", "crash", "fire", "numberOfInjuries", "numberOfDeaths", "summary"]
CTX = None


def make_ssl_context(insecure=False):
    if insecure:
        return ssl._create_unverified_context()
    try:
        import certifi
        return ssl.create_default_context(cafile=certifi.where())
    except Exception:
        return ssl.create_default_context()


def fetch(make, model, year):
    qs = urllib.parse.urlencode({"make": make, "model": model, "modelYear": year})
    with urllib.request.urlopen(f"{API}?{qs}", timeout=90, context=CTX) as r:
        return json.load(r)


def years_arg(s):
    if "-" in s:
        a, b = s.split("-"); return list(range(int(a), int(b) + 1))
    return [int(x) for x in s.split(",")]


def clean(v):
    s = "" if v is None else str(v)
    return s.replace("\r", " ").replace("\n", " ").strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--make", default="hyundai")
    ap.add_argument("--models", default="elantra,sonata,tucson,santa fe,palisade,kona")
    ap.add_argument("--years", default="2020-2022")
    ap.add_argument("--sleep", type=float, default=0.3)
    ap.add_argument("--insecure", action="store_true", help="맥 SSL 우회")
    ap.add_argument("--max-per", type=int, default=80, help="모델×연식당 최대 행(CSV 비대 방지)")
    args = ap.parse_args()

    global CTX
    CTX = make_ssl_context(args.insecure)
    os.makedirs(RAW_DIR, exist_ok=True)
    models = [m.strip() for m in args.models.split(",") if m.strip()]
    years = years_arg(args.years)

    rows, seen = [], set()
    for model in models:
        for year in years:
            try:
                data = fetch(args.make, model, year)
            except Exception as e:
                print(f"  ! {model} {year}: {e}"); continue
            results = data.get("results", [])[: args.max_per]
            raw_path = os.path.join(RAW_DIR, f"{model.replace(' ','_')}_{year}.json")
            with open(raw_path, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
            print(f"  + {model} {year}: {len(results)}건")
            for rec in results:
                odi = rec.get("odiNumber")
                if odi in seen:
                    continue
                seen.add(odi)
                rows.append({
                    "odiNumber": clean(odi),
                    "dateComplaintFiled": clean(rec.get("dateComplaintFiled")),
                    "make": args.make.upper(),
                    "model": model.upper(),
                    "modelYear": year,
                    "components": clean(rec.get("components")),
                    "crash": clean(rec.get("crash")),
                    "fire": clean(rec.get("fire")),
                    "numberOfInjuries": clean(rec.get("numberOfInjuries")),
                    "numberOfDeaths": clean(rec.get("numberOfDeaths")),
                    "summary": clean(rec.get("summary"))[:500],
                })
            time.sleep(args.sleep)

    csv_path = os.path.join(OUT_DIR, "hyundai_complaints_nhtsa.csv")
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS); w.writeheader(); w.writerows(rows)
    print(f"\n[done] 원본: {os.path.relpath(RAW_DIR, ROOT)}/  |  CSV {len(rows)}행: {os.path.relpath(csv_path, ROOT)}")


if __name__ == "__main__":
    main()
