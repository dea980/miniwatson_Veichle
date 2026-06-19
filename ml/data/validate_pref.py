#!/usr/bin/env python3
"""
선호쌍(DPO) 데이터 검증 — JSONL 형식 + 스키마 + 품질 게이트.

DPO 학습 전에 pref_seed.jsonl이 깨지지 않았는지, 선호 신호가 의미 있는지 확인한다.
("데이터셋 구축 + automatic evaluation"의 일부 — 학습 전 품질 게이트)

사용:  python3 ml/data/validate_pref.py [경로]   (기본: ml/data/pref_seed.jsonl)
종료코드: 문제 있으면 1 (CI 게이트로 쓸 수 있음)
"""
import json, os, re, sys

NEED = {"prompt", "chosen", "rejected"}
CJK = re.compile(r"[一-鿿]")   # 중국어/한자 — chosen(좋은 답)엔 없어야 함

path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "pref_seed.jsonl")

ok = bad = warn = 0
if not os.path.exists(path):
    print(f"[validate] 파일 없음: {path}"); sys.exit(1)

for i, line in enumerate(open(path, encoding="utf-8"), 1):
    s = line.strip()
    if not s:
        continue
    # 1) JSON 파싱 (주석 '//' 같은 비 JSON 줄을 여기서 잡음 — JSONL은 주석 불가)
    try:
        d = json.loads(s)
    except json.JSONDecodeError as e:
        print(f"  ✗ line {i}: JSON 오류 — {e} (JSONL은 //주석·트레일링콤마 불가)")
        bad += 1
        continue
    # 2) 스키마: 필수 키 + 비어있지 않음
    miss = NEED - set(d)
    if miss:
        print(f"  ✗ line {i}: 키 누락 {miss}"); bad += 1; continue
    if not all(str(d[k]).strip() for k in NEED):
        print(f"  ✗ line {i}: 빈 값 존재"); bad += 1; continue
    # 3) 품질 경고: chosen=좋은 답인데 중국어가 섞이면 신호 오염 / chosen==rejected면 무의미
    if CJK.search(d["chosen"]):
        print(f"  ⚠ line {i}: chosen에 한자/중국어 — 좋은 답에 누수(선호 신호 약화)"); warn += 1
    if d["chosen"].strip() == d["rejected"].strip():
        print(f"  ⚠ line {i}: chosen == rejected (선호 차이 없음 → 학습 신호 0)"); warn += 1
    ok += 1

print(f"\n[validate] 유효 {ok}쌍, 오류 {bad}건, 경고 {warn}건  ({path})")
if ok < 10:
    print(f"  ⚠ {ok}쌍 — DPO 데모엔 최소 10쌍 권장")
sys.exit(1 if bad else 0)
