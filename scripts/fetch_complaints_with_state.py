# scripts/fetch_complaints_with_state.py  (참고 구현 v2 — PII 최소화)
import csv, io, re, urllib.request, zipfile
from datetime import datetime
URL = "https://static.nhtsa.gov/odi/ffdd/cmpl/FLAT_CMPL.zip"   # 전체. 느리면 COMPLAINTS_RECEIVED_2020-2024.zip
OUT = "data/vehicle/complaints/hyundai_complaints_nhtsa.csv"

# 인덱스 = 문서필드번호 − 1 (문서 1-based, 파이썬 0-based)
F = {
    "odino":1,        # #2  ODINO
    "make":3,         # #4  MAKETXT
    "model":4,        # #5  MODELTXT
    "year":5,         # #6  YEARTXT
    "crash":6,        # #7  CRASH
    "faildate":7,     # #8  FAILDATE (사고일)
    "fire":8,         # #9  FIRE
    "injured":9,      # #10 INJURED
    "deaths":10,      # #11 DEATHS
    "compdesc":11,    # #12 COMPDESC
    "state":13,       # #14 STATE (거주 주, 2자리 — 비PII)
    "ldate":16,       # #17 LDATE (접수일)
    "miles":17,       # #18 MILES
    "cdescr":19,      # #20 CDESCR (자유텍스트 → 스크럽)
    "drive_train":27, # #28 DRIVE_TRAIN
    "fuel_type":29,   # #30 FUEL_TYPE (HE=하이브리드전기 → EV 렌즈)
    "trans_type":30,  # #31 TRANS_TYPE
    "state_incident":49,  # #50 STATE_OF_INCIDENT (사고 발생 주, 신규)
}
# 의도적 미수집(PII): #13 CITY, #15 VIN, #41-45 DEALER_*, #51 VEHICLE_OPERATOR

## datetime
def to_mdy(v):
    s = (v or "").strip()
    if len(s) != 8 or not s.isdigit(): return ""
    try: return datetime.strptime(s, "%Y%m%d").strftime("%m/%d/%Y")
    except ValueError: return ""


# 자유텍스트 1차 스크럽(커밋 아티팩트 안전용). 깊은 리댁션은 적재단(Java)이 담당.
EMAIL = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")
PHONE = re.compile(r"\b\d{3}[-.\s]?\d{3}[-.\s]?\d{4}\b")
VINRE = re.compile(r"\b[A-HJ-NPR-Z0-9]{17}\b")   # VIN 17자리 패턴
def scrub(t):
    t = EMAIL.sub("[EMAIL]", t)
    t = PHONE.sub("[PHONE]", t)
    t = VINRE.sub("[VIN]", t)
    return t.replace("\r"," ").replace("\n"," ").strip()

def yn(v): return "true" if (v or "").strip().upper() == "Y" else "false"

rows = []
with urllib.request.urlopen(URL) as resp:
    zf = zipfile.ZipFile(io.BytesIO(resp.read()))
    name = [n for n in zf.namelist() if n.lower().endswith(".txt")][0]
    with zf.open(name) as fh:
        text = io.TextIOWrapper(fh, encoding="latin-1", errors="replace")  # 안 되면 cp1252
        for line in text:
            c = line.rstrip("\n").split("\t")
            if len(c) < 50:                     # CDESCR 개행으로 깨진 줄 방어
                continue
            if c[F["make"]].strip().upper() != "HYUNDAI":
                continue
            rows.append({
                "odiNumber":          c[F["odino"]].strip(),
                "dateComplaintFiled": to_mdy(c[F["ldate"]]),
                "make":               c[F["make"]].strip(),
                "model":              c[F["model"]].strip(),
                "modelYear":          c[F["year"]].strip(),
                "components":         c[F["compdesc"]].strip(),
                "crash":              yn(c[F["crash"]]),
                "fire":               yn(c[F["fire"]]),
                "numberOfInjuries":   c[F["injured"]].strip() or "0",
                "numberOfDeaths":     c[F["deaths"]].strip() or "0",
                "summary":            scrub(c[F["cdescr"]]),          # 스크럽 적용
                # ── 신규(비PII) 분석 컬럼 ──
                "state":              c[F["state"]].strip(),
                "stateOfIncident":    c[F["state_incident"]].strip(),
                "fuelType":           c[F["fuel_type"]].strip(),
                "driveTrain":         c[F["drive_train"]].strip(),
                "transType":          c[F["trans_type"]].strip(),
                "failDate":           to_mdy(c[F["faildate"]]),
                "miles":              c[F["miles"]].strip() or "0",
            })

with open(OUT, "w", newline="", encoding="utf-8") as f:
    w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
    w.writeheader(); w.writerows(rows)
print(f"{len(rows)}건 저장 → {OUT}  (PII 컬럼 미수집 + summary 스크럽)")