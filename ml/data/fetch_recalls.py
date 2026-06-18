#!/usr/bin/env python3
"""
NHTSA 리콜 원본 수집기 (API 키 불필요, 공개).

  - 원본 JSON 그대로 저장: data/vehicle/recalls/raw/{model}_{year}.json
  - 가공 CSV(평탄화) 저장 : data/vehicle/recalls/hyundai_recalls_nhtsa.csv

API: https://api.nhtsa.gov/recalls/recallsByVehicle?make=&model=&modelYear=
  (make/model/modelYear 3개 필수 → 모델×연식을 순회 호출)

사용:
  python3 ml/data/fetch_recalls.py                       # 기본 세트(현대 인기차종 x 최근연식)
  python3 ml/data/fetch_recalls.py --make hyundai --models "elantra,sonata,tucson,santa fe,palisade,kona,ioniq" --years 2019-2024
"""
import argparse, csv, json, os, ssl, time, urllib.parse, urllib.request

API = "https://api.nhtsa.gov/recalls/recallsByVehicle"


def make_ssl_context(insecure=False):
    """맥 Python은 CA 번들이 연결 안 돼 SSL 검증 실패가 잦다.
    1) certifi 있으면 그걸로 정상 검증, 2) --insecure면 검증 생략."""
    if insecure:
        return ssl._create_unverified_context()
    try:
        import certifi
        return ssl.create_default_context(cafile=certifi.where())
    except Exception:
        return ssl.create_default_context()


CTX = None  # main에서 설정
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT_DIR = os.path.join(ROOT, "data", "vehicle", "recalls")
RAW_DIR = os.path.join(OUT_DIR, "raw")

# CSV로 평탄화할 원본 필드(원본 키 그대로)
FIELDS = ["NHTSACampaignNumber", "ReportReceivedDate", "Make", "Model", "ModelYear",
          "Component", "parkIt", "parkOutSide", "overTheAirUpdate",
          "Summary", "Consequence", "Remedy", "Notes", "Manufacturer"]


def fetch(make, model, year):
    qs = urllib.parse.urlencode({"make": make, "model": model, "modelYear": year})
    with urllib.request.urlopen(f"{API}?{qs}", timeout=60, context=CTX) as r:
        return json.load(r)


def years_arg(s):
    if "-" in s:
        a, b = s.split("-"); return list(range(int(a), int(b) + 1))
    return [int(x) for x in s.split(",")]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--make", default="hyundai")
    ap.add_argument("--models",
                    default="elantra,sonata,tucson,santa fe,palisade,kona,ioniq,accent,venue")
    ap.add_argument("--years", default="2019-2024")
    ap.add_argument("--sleep", type=float, default=0.3)   # 예의상 호출 간격
    ap.add_argument("--insecure", action="store_true",
                    help="맥 SSL 인증서 오류 우회(검증 생략). certifi 설치가 정석.")
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
            results = data.get("results", [])
            # 원본 JSON 그대로 보존
            raw_path = os.path.join(RAW_DIR, f"{model.replace(' ','_')}_{year}.json")
            with open(raw_path, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
            print(f"  + {model} {year}: {len(results)}건 → {os.path.relpath(raw_path, ROOT)}")
            for rec in results:
                key = (rec.get("NHTSACampaignNumber"), rec.get("Model"), rec.get("ModelYear"))
                if key in seen:
                    continue
                seen.add(key)
                rows.append({k: rec.get(k, "") for k in FIELDS})
            time.sleep(args.sleep)

    csv_path = os.path.join(OUT_DIR, "hyundai_recalls_nhtsa.csv")
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS); w.writeheader(); w.writerows(rows)
    print(f"\n[done] 원본 JSON: {os.path.relpath(RAW_DIR, ROOT)}/  |  CSV {len(rows)}행: {os.path.relpath(csv_path, ROOT)}")


if __name__ == "__main__":
    main()
