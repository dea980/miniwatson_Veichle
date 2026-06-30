#!/usr/bin/env python3
"""
현대 한국 디지털 취급설명서(공식 PDF) 다운로드 → 지식베이스(RAG) 인제스트.

소스: ml/data/owners_manuals_hyundai_kr.csv (ownersmanual.hyundai.com/full_pdf/{projectCode}/{year}/ko_KR)
      Claude in Chrome로 포털 내부 API(/api/v2/hmc/model/owners-manuals)를 역추적해 확보한 직접 PDF URL.

기존 KB는 2016 영어·내연 매뉴얼뿐이라 전기/수소/하이브리드·신형(2020~2026) 한국어 매뉴얼로 공백을 메운다.

사용:
    # 1) 다운로드만 (data/vehicle/manuals/ 에 저장)
    python scripts/fetch_ingest_kr_manuals.py
    # 2) 다운로드 + KB 인제스트 (백엔드 실행 중이어야 함, namespace=vehicle)
    python scripts/fetch_ingest_kr_manuals.py --ingest
    # 일부만: 전기/하이브리드 우선 등
    python scripts/fetch_ingest_kr_manuals.py --ingest --models "아이오닉 5,아이오닉 6,NEXO,코나 Electric,팰리세이드 Hybrid"
    python scripts/fetch_ingest_kr_manuals.py --ingest --limit 8

주의: 매뉴얼 1권이 10~30MB(수백 페이지)다. 전체(37권) 인제스트는 로컬 임베딩 부하가 크니
      처음엔 --limit 또는 --models로 핵심부터 넣기를 권장.
"""
import argparse
import csv
import os
import ssl
import sys
import time
import urllib.request
import uuid

MANUALS_DIR = "data/vehicle/manuals"
MANIFEST = "ml/data/owners_manuals_hyundai_kr.csv"
# 포트 충돌(Airflow가 8080 점유 등) 대비 — MW_INGEST_URL 환경변수로 덮어쓰기 가능.
INGEST_URL = os.environ.get("MW_INGEST_URL", "http://localhost:8080/api/data/ingest-file")


def make_ssl_context():
    # certifi 번들이 있으면 그걸 쓰고, 없으면 시스템 trust store로 폴백.
    # TLS 검증은 절대 끄지 않는다(중간자 위변조 PDF 적재 차단). certifi/시스템 모두 실패하면
    # 연결이 명시적으로 실패하도록 둔다 → 운영자가 `pip install certifi` 로 해결.
    try:
        import certifi
        return ssl.create_default_context(cafile=certifi.where())
    except ImportError:
        print("[kr-manuals] certifi 없음 — 시스템 trust store 사용(검증 유지). 실패 시 `pip install certifi`")
        return ssl.create_default_context()


SSL_CTX = make_ssl_context()


def download(url, dest):
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 miniwatson-manuals/1.0",
        "Referer": "https://ownersmanual.hyundai.com/",
    })
    with urllib.request.urlopen(req, timeout=120, context=SSL_CTX) as r:
        data = r.read()
        ctype = r.headers.get("content-type", "")
    if "pdf" not in ctype.lower() and not data[:4] == b"%PDF":
        raise RuntimeError(f"PDF 아님(content-type={ctype}, {len(data)}B)")
    with open(dest, "wb") as f:
        f.write(data)
    return len(data)


def ingest(path, namespace="vehicle"):
    """stdlib multipart POST → /api/data/ingest-file (file, namespace)."""
    boundary = "----mw" + uuid.uuid4().hex
    with open(path, "rb") as f:
        content = f.read()
    fn = os.path.basename(path)
    pre = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"namespace\"\r\n\r\n{namespace}\r\n"
           f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{fn}\"\r\n"
           f"Content-Type: application/pdf\r\n\r\n").encode("utf-8")
    post = f"\r\n--{boundary}--\r\n".encode("utf-8")
    body = pre + content + post
    req = urllib.request.Request(INGEST_URL, data=body, method="POST",
                                 headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    with urllib.request.urlopen(req, timeout=600) as r:
        return r.status


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ingest", action="store_true", help="다운로드 후 KB 인제스트(백엔드 실행 필요)")
    ap.add_argument("--models", default=None, help="콤마구분 모델명 필터(부분일치)")
    ap.add_argument("--limit", type=int, default=0, help="처리할 최대 개수(0=전체)")
    ap.add_argument("--manifest", default=MANIFEST)
    args = ap.parse_args()

    if not os.path.exists(args.manifest):
        print(f"[kr-manuals] 매니페스트 없음: {args.manifest}")
        sys.exit(1)
    os.makedirs(MANUALS_DIR, exist_ok=True)

    rows = []
    with open(args.manifest, encoding="utf-8") as f:
        for row in csv.DictReader(filter(lambda l: not l.lstrip().startswith("#"), f)):
            if row.get("url"):
                rows.append(row)
    if args.models:
        want = [m.strip() for m in args.models.split(",") if m.strip()]
        rows = [r for r in rows if any(w in r["model"] for w in want)]
    if args.limit > 0:
        rows = rows[:args.limit]

    print(f"[kr-manuals] 대상 {len(rows)}권 (다운로드{' + 인제스트' if args.ingest else ''})")
    ok = dl = ing = 0
    for r in rows:
        dest = os.path.join(MANUALS_DIR, r["filename"])
        try:
            if os.path.exists(dest) and os.path.getsize(dest) > 100000:
                print(f"  = {r['model']}: 이미 있음 ({dest})")
            else:
                size = download(r["url"], dest)
                dl += 1
                print(f"  ↓ {r['model']} ({r['year']}): {size//1024//1024}MB → {dest}")
            ok += 1
        except Exception as e:
            print(f"  ! {r['model']} 다운로드 실패: {e}")
            continue
        if args.ingest:
            try:
                st = ingest(dest)
                ing += 1
                print(f"    ✓ 인제스트 {st} (namespace=vehicle)")
            except Exception as e:
                print(f"    ! 인제스트 실패(백엔드 실행 확인): {e}")
        time.sleep(0.3)

    print(f"[kr-manuals] 완료: 확보 {ok}/{len(rows)}, 신규 다운로드 {dl}, 인제스트 {ing}")
    if not args.ingest:
        print("[kr-manuals] KB 적재하려면 --ingest 로 다시 실행(백엔드 실행 상태에서).")


if __name__ == "__main__":
    main()
