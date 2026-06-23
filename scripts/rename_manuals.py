#!/usr/bin/env python3
"""
매뉴얼 PDF 파일명 표준화 — dry-run / apply.

목표 규칙:
    hyundai_<year>_<model_romanized>[_<powertrain>]_<code>_owners_<REGION>.pdf

예:
    AX_2025_ko_KR.pdf           → hyundai_2025_casper_AX_owners_KR.pdf
    CN7HEV_2025_ko_KR.pdf       → hyundai_2025_avante_hybrid_CN7HEV_owners_KR.pdf
    AEEV_2020_ko_KR.pdf         → hyundai_2020_ioniq_electric_AEEV_owners_KR.pdf

매니페스트(ml/data/owners_manuals_hyundai_kr.csv)의 `filename` 컬럼이
이미 `hyundai_kr_<model[_powertrain]>_<code>_<year>.pdf` 형태라 거기서 model·powertrain을 뽑는다.
매핑에 없는 코드는 코드 그대로 model로 사용(예: hyundai_2014_AG_owners_KR.pdf).

사용:
    python scripts/rename_manuals.py           # dry-run
    python scripts/rename_manuals.py --apply   # 실제 mv 수행
"""
import argparse, csv, os, re, sys, shutil

MANIFEST = "ml/data/owners_manuals_hyundai_kr.csv"
MANUALS_DIR = "data/vehicle/manuals"
PAT = re.compile(r"^(?P<code>[A-Z0-9]+)_(?P<year>\d{4})_(?P<lang>[a-z]{2})_(?P<region>[A-Z]{2})\.pdf$")

POWERTRAINS = ("phev", "hybrid", "electric", "fcev")   # 가장 긴 토큰부터 매칭

# 매니페스트(현행 신차 37코드)에 없는 단종/구형/상용 모델의 프로젝트코드 → (model, powertrain) 보강.
# 출처: 현대 KMA 프로젝트 코드 통상 표기. 확실치 않은 코드는 비워두고 dry-run에서 "코드만"으로 표시.
LEGACY_CODES = {
    # 승용 단종/구형
    "AD":     ("avante", None),         # 아반떼 AD (2015~2020)
    "MD":     ("avante", None),         # 아반떼 MD (2010~2015)
    "LF":     ("sonata", None),         # 쏘나타 LF (2014~2019)
    "LFHEV":  ("sonata", "hybrid"),     # 쏘나타 LF HEV
    "LFPHEV": ("sonata", "phev"),       # 쏘나타 LF PHEV
    "YF":     ("sonata", None),         # 쏘나타 YF (2009~2014)
    "YFHEV":  ("sonata", "hybrid"),     # 쏘나타 YF HEV
    "HG":     ("grandeur", None),       # 그랜저 HG (2011~2017)
    "HGHEV":  ("grandeur", "hybrid"),   # 그랜저 HG HEV
    "IG":     ("grandeur", None),       # 그랜저 IG (2016~2022)
    "IGHEV":  ("grandeur", "hybrid"),   # 그랜저 IG HEV
    "AG":     ("aslan", None),          # 아슬란 AG (2014~2017)
    "BH":     ("genesis", None),        # 제네시스 BH 세단 (2008~2013)
    "RB":     ("accent", None),         # 엑센트 RB (2010~2017)
    "FD":     ("i30", None),            # i30 FD (2007~2011)
    "FS":     ("i30", None),            # i30 FS (2017~)
    # SUV 단종/구형
    "DM":     ("santafe", None),        # 싼타페 DM (2013~2018)
    "TM":     ("santafe", None),        # 싼타페 TM (2018~2023)
    "TMHEV":  ("santafe", "hybrid"),    # 싼타페 TM HEV
    "TL":     ("tucson", None),         # 투싼 TL (2015~2020)
    "LM":     ("ix35", None),           # 투싼 ix/ix35 LM (2009~2015)
    "LMFC":   ("ix35", "fcev"),         # 투싼 ix FCEV
    "OS":     ("kona", None),           # 코나 OS (2017~2022)
    "OSHEV":  ("kona", "hybrid"),       # 코나 OS HEV
    "LX2":    ("palisade", None),       # 팰리세이드 LX2 (2018~2022)
    # VI: 확정 불가(2012~2015·4파일) — 코드-fallback 유지
    # 이오니크(IONIQ) 1세대 — AEEV/AEPHEV는 매니페스트 있음, HEV만 누락
    "AEHEV":  ("ioniq", "hybrid"),
    # 상용/특수(이름 확정 가능한 것만)
    "US4EV":  ("staria", "electric"),
    "PY":     ("universe", None),
    "PYFCEV": ("universe", "fcev"),
    "QZ":     ("xcient", None),
    "QZFCEV": ("xcient", "fcev"),
    "CY":     ("mighty", None),
    "CYEV":   ("mighty", "electric"),
    "CYFCEV": ("mighty", "fcev"),
    # PDF 메타데이터/본문 키워드로 확정 (2026-06 조사)
    "CS":     ("county", None),               # PDF title: county-manual-*
    "CSEV":   ("county", "electric"),         # PDF title: county-ev-manual-*
    "EG":     ("newpower", None),             # PDF title: newpower-manual-* (뉴파워트럭)
    "EU":     ("solati", None),               # PDF title: solati-manual-* (쏠라티)
    "JO4":    ("eleccity_doubledecker", None),# PDF title: eleccity-double-decker-* (일렉시티 2층버스)
    "QT":     ("mighty", None),               # PDF title: mighty-manual-*
    "QV":     ("pavise", None),               # PDF title: pavise-manual-* (파비스)
    "POREST": ("porter_est", None),           # 본문 다회 "포터" — 포터 EST 변형
    "US4SV":  ("staria", "sv"),               # US4=staria base + 본문 "스타리아"
    "GYEV":   ("gy", "electric"),             # "구동용 고전압" 키워드 → EV 확정, 차종명 미상(코드 유지)
    # 사용자가 현대 공식 포털·웹으로 확정 (2026-06)
    "EA":     ("blueon", None),               # 블루온 — 현대 첫 양산 EV (2010)
    "EN":     ("veracruz", None),             # 베라크루즈 (Veracruz)
    "HDHEV":  ("avante", "hybrid"),           # HD=아반떼 4세대 base + LPi 하이브리드 (2009 — 현대 최초 양산 HEV)
    "HR":     ("porter2", None),              # 포터 II (상용)
    "HRSV":   ("porter2", "sv"),              # 포터 II 특수차 — HR base + 본문 "포터" 검출
    "JS":     ("veloster", None),             # 벨로스터 2세대 (JS, 2018~)
    "NC":     ("maxcruz", None),              # 맥스크루즈 (Maxcruz / Grand Santa Fe)
    "VF":     ("i40", None),                  # i40 (2011~2019)
}


def load_code_map(path):
    """code -> (model_romanized, powertrain_or_None) 추출. 매니페스트 filename에서 파싱."""
    out = {}
    if not os.path.exists(path):
        return out
    with open(path, encoding="utf-8") as f:
        for row in csv.DictReader(filter(lambda l: not l.lstrip().startswith("#"), f)):
            fn = (row.get("filename") or "").strip()
            code = (row.get("projCode") or row.get("projcode") or "").strip()
            if not fn or not code:
                continue
            # 매니페스트 filename 두 형식 모두 처리:
            #  (구) hyundai_kr_<model>[_<powertrain>]_<code>_<year>.pdf
            #  (신) hyundai_<year>_<model>[_<powertrain>]_owners_kr.pdf  ← 2026~ 갱신본
            base = fn[:-4] if fn.endswith(".pdf") else fn
            parts = base.split("_")
            mid = []
            if len(parts) >= 5 and parts[0] == "hyundai" and parts[-2].lower() == "owners":
                # 신형: tokens [hyundai, <year>, <model...>, owners, <region>]
                mid = parts[2:-2]
                # 첫 토큰이 연도면 떨굼(중복 안전)
                if mid and mid[0].isdigit() and len(mid[0]) == 4: mid = mid[1:]
            elif code and code in parts:
                # 구형: code 위치 기준 분리
                ci = parts.index(code)
                mid = parts[2:ci]
            if not mid:
                continue
            powertrain = None
            if mid[-1].lower() in POWERTRAINS:
                powertrain = mid[-1].lower()
                model_parts = mid[:-1]
            else:
                model_parts = mid
            model = "_".join(model_parts).lower()
            out[code.upper()] = (model, powertrain)
    # 매니페스트에 없는 단종/구형/상용 코드 보강 (manifest가 우선, 덮어쓰지 않음)
    for c, mp in LEGACY_CODES.items():
        out.setdefault(c, mp)
    return out


def target_name(src_name, code_map):
    m = PAT.match(src_name)
    if not m:
        return None, "패턴 불일치"
    code = m.group("code").upper()
    year = m.group("year")
    region = m.group("region").upper()
    mapping = code_map.get(code)
    if mapping:
        model, powertrain = mapping
        parts = ["hyundai", year, model]
        if powertrain: parts.append(powertrain)
        parts += [code, "owners", region]
        return "_".join(parts) + ".pdf", None
    # 매니페스트에 없는 옛 코드: 코드 자체를 model로(소문자), powertrain 없음
    return f"hyundai_{year}_{code.lower()}_{code}_owners_{region}.pdf", "코드만(매핑 없음)"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true", help="실제 mv 수행")
    ap.add_argument("--manifest", default=MANIFEST)
    ap.add_argument("--dir", default=MANUALS_DIR)
    args = ap.parse_args()

    code_map = load_code_map(args.manifest)
    print(f"[map] 매니페스트 매핑 코드 수: {len(code_map)}")

    files = sorted(os.listdir(args.dir))
    pdfs = [f for f in files if f.lower().endswith(".pdf")]
    mapped = unmapped = skip = collisions = 0
    plan = []
    collisions_list = []
    for f in pdfs:
        new, note = target_name(f, code_map)
        if not new:
            skip += 1
            continue
        if new == f:
            skip += 1
            continue
        dest = os.path.join(args.dir, new)
        if os.path.exists(dest):
            collisions += 1
            collisions_list.append((f, new))
            continue
        plan.append((f, new, note))
        if note: unmapped += 1
        else: mapped += 1

    print(f"[scan] pdfs={len(pdfs)} → 변경예정 {len(plan)} (mapped {mapped}, unmapped {unmapped}), 충돌 {collisions}, 동일/스킵 {skip}")
    if collisions_list:
        print("[collisions] 동일 대상이 이미 존재 — 수동 확인 필요:")
        for a, b in collisions_list[:10]:
            print(f"  - {a} → {b}")
    # 샘플 50개
    for f, new, note in plan[:50]:
        tag = f" ({note})" if note else ""
        print(f"  {f}  →  {new}{tag}")
    if len(plan) > 50:
        print(f"  ... +{len(plan)-50} more")

    # 어떤 모드든 DB UPDATE SQL은 산출(나중에 H2 console·psql에 붙여넣기). 청크 title은 "<filename> #N" 형태.
    sql_path = "scripts/migrate_manual_titles.sql"
    with open(sql_path, "w", encoding="utf-8") as sf:
        sf.write("-- 매뉴얼 파일명 표준화에 따른 DB title 마이그레이션\n")
        sf.write("-- 적용: H2 console(http://localhost:8080/h2-console) 또는 psql 에 그대로 실행.\n")
        sf.write("-- 청크 title은 \"<filename> #N\" 형태이므로 REPLACE 로 접두만 갈아끼움.\n\n")
        for old, new, _ in plan:
            old_e = old.replace("'", "''")
            new_e = new.replace("'", "''")
            sf.write(f"UPDATE article SET title = REPLACE(title, '{old_e}', '{new_e}') WHERE title LIKE '{old_e}%';\n")
            sf.write(f"UPDATE document_catalog SET title = '{new_e}' WHERE title = '{old_e}';\n")
    print(f"[sql] DB UPDATE 스크립트: {sql_path}  (UPDATE {len(plan)*2}건)")

    if not args.apply:
        print("[dry-run] --apply 로 실행하면 실제 mv 수행. 그 후 위 SQL로 DB title도 갱신.")
        return

    done = 0
    for f, new, note in plan:
        shutil.move(os.path.join(args.dir, f), os.path.join(args.dir, new))
        done += 1
    print(f"[apply] mv 완료 {done}/{len(plan)}")
    print(f"[next] {sql_path} 를 H2 console(http://localhost:8080/h2-console) 에 실행하여 DB title 갱신")


if __name__ == "__main__":
    main()
