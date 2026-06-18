#!/usr/bin/env python3
"""
매뉴얼 PDF 수집기 — 매니페스트(URL 목록) 기반 일괄 다운로드.

리콜은 API라 자동순회가 되지만, 매뉴얼 PDF는 사이트마다 구조가 달라
'받을 PDF의 직접 링크'를 목록(매니페스트)에 적어두고 일괄 내려받는 방식이 정석·재현가능.

입력 : ml/data/manuals_manifest.csv   (헤더: url,filename,source)
출력 : data/vehicle/manuals/*.pdf      (RAG 인제스트 대상)
       data/vehicle/manuals/_provenance.csv  (출처·크기·해시 기록)

사용:
  python3 ml/data/fetch_manuals.py
  python3 ml/data/fetch_manuals.py --manifest ml/data/manuals_manifest.csv --insecure
  python3 ml/data/fetch_manuals.py --url "https://.../elantra.pdf" --filename elantra_2021.pdf
"""
import argparse, csv, hashlib, os, ssl, urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MAN_DIR = os.path.join(ROOT, "data", "vehicle", "manuals")
DEFAULT_MANIFEST = os.path.join(os.path.dirname(__file__), "manuals_manifest.csv")
UA = "Mozilla/5.0 (miniwatson-vehicle research; +local)"
CTX = None


def make_ssl_context(insecure=False):
    if insecure:
        return ssl._create_unverified_context()
    try:
        import certifi
        return ssl.create_default_context(cafile=certifi.where())
    except Exception:
        return ssl.create_default_context()


def download(url, dst):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=120, context=CTX) as r:
        data = r.read()
    # PDF 검증: 매직넘버 %PDF (HTML 에러페이지를 PDF로 오인 저장 방지)
    if not data[:4] == b"%PDF":
        raise ValueError(f"PDF 아님(앞 4바이트={data[:4]!r}) — 링크가 직접 PDF인지 확인")
    with open(dst, "wb") as f:
        f.write(data)
    return len(data), hashlib.sha256(data).hexdigest()[:12]


def fname_from_url(url):
    base = url.split("?")[0].rstrip("/").split("/")[-1]
    return base if base.lower().endswith(".pdf") else base + ".pdf"


def load_jobs(args):
    if args.url:
        return [{"url": args.url,
                 "filename": args.filename or fname_from_url(args.url),
                 "source": "cli"}]
    if not os.path.exists(args.manifest):
        print(f"매니페스트 없음: {args.manifest}\n→ url,filename,source 형식으로 받을 PDF 링크를 채워주세요.")
        return []
    jobs = []
    with open(args.manifest, encoding="utf-8") as f:
        for row in csv.DictReader(f):
            url = (row.get("url") or "").strip()
            if not url or url.startswith("#"):
                continue
            jobs.append({"url": url,
                         "filename": (row.get("filename") or "").strip() or fname_from_url(url),
                         "source": (row.get("source") or "").strip()})
    return jobs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", default=DEFAULT_MANIFEST)
    ap.add_argument("--url", help="단건 다운로드")
    ap.add_argument("--filename", help="단건 저장 파일명")
    ap.add_argument("--insecure", action="store_true", help="맥 SSL 우회")
    args = ap.parse_args()

    global CTX
    CTX = make_ssl_context(args.insecure)
    os.makedirs(MAN_DIR, exist_ok=True)

    jobs = load_jobs(args)
    if not jobs:
        return

    prov = []
    for j in jobs:
        dst = os.path.join(MAN_DIR, j["filename"])
        if os.path.exists(dst):
            print(f"  = 이미 있음: {j['filename']}"); continue
        try:
            n, h = download(j["url"], dst)
            print(f"  + {j['filename']}  ({n//1024} KB, sha {h})")
            prov.append({"filename": j["filename"], "url": j["url"],
                         "source": j["source"], "bytes": n, "sha256_12": h})
        except Exception as e:
            print(f"  ! 실패 {j['filename']}: {e}")

    if prov:
        ppath = os.path.join(MAN_DIR, "_provenance.csv")
        exists = os.path.exists(ppath)
        with open(ppath, "a", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=["filename", "url", "source", "bytes", "sha256_12"])
            if not exists:
                w.writeheader()
            w.writerows(prov)
        print(f"\n[done] {len(prov)}개 저장 → {os.path.relpath(MAN_DIR, ROOT)}/  (출처: _provenance.csv)")
        print("다음: 앱 켠 뒤  bash ml/data/ingest_vehicle.sh  로 RAG 인제스트")


if __name__ == "__main__":
    main()
