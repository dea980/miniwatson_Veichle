#!/usr/bin/env python3
"""
CJK 누수 회귀 검사 (PRD §5 '외국문자 누수율 0%').

소형/FT 모델이 한국어 답변에 한자·히라가나·가타카나를 섞어 내는 버그가 있었다(수嗫菲, 请使用韩语…).
이 스크립트는 출력 문자열에 그런 외국(CJK) 문자가 있는지 검사한다. 한글(가~힣)은 정상으로 통과.

모드:
  1) self-test (기본, 서버 불필요):  python3 eval/check_cjk.py
       - 알려진 나쁜/좋은 샘플로 판별 로직을 검증. CI에서 서버 없이도 회귀 방지.
  2) live (서버 필요):              python3 eval/check_cjk.py --live
       - /api/rag/ask 등 실제 응답을 받아 CJK 누수 0을 단언. API=... 로 호스트 지정.

종료코드: 누수/실패가 있으면 1, 모두 통과면 0 (CI 게이트로 사용).
"""
import os, re, sys, json, urllib.request

# 한자(통합/확장A/호환) + 히라가나 + 가타카나. 한글(AC00–D7A3)·자모는 제외.
CJK = re.compile(r"[぀-ヿ㐀-䶿一-鿿豈-﫿]")

def foreign_chars(text: str):
    """문자열에서 발견된 외국(CJK) 문자들의 집합을 반환(없으면 빈 set)."""
    return sorted(set(CJK.findall(text or "")))

def check(label: str, text: str) -> bool:
    """CJK가 없으면 True. 있으면 어떤 문자인지 출력하고 False."""
    bad = foreign_chars(text)
    if bad:
        sample = (text or "")[:80].replace("\n", " ")
        print(f"  ✗ {label}: 외국문자 {bad[:8]} — \"{sample}…\"")
        return False
    print(f"  ✓ {label}: clean")
    return True

def self_test() -> int:
    print("[self-test] CJK 판별 로직 검증 (서버 불필요)")
    good = [
        "안전벨트 경고등은 좌석 미착용 시 점등됩니다.",
        "SANTA FE 2024년형, ABS 경고등 관련 점검 항목입니다.",
        "부품 3개, 공임 2.5h, 합계 120,000원.",
    ]
    bad = [
        "2024년형 현대 수嗫菲",                       # 고유명사 한자 오역
        "请使用韩语回答，并且不要包含中文。",           # 시스템 지시 중국어 누수
        "에어백 センサー 점검",                        # 가타카나 누수
    ]
    ok = True
    for i, g in enumerate(good):
        ok &= check(f"good[{i}]", g)
    for i, b in enumerate(bad):
        # 나쁜 샘플은 '검출되어야' 정상 → check가 False를 반환해야 통과
        detected = not check(f"bad[{i}] (검출 기대)", b)
        if detected:
            print(f"    → 기대대로 검출됨 (정상)")
        else:
            print(f"    → 검출 실패! 회귀 로직 문제")
        ok &= detected
    print("PASS ✅" if ok else "FAIL ❌")
    return 0 if ok else 1

def live() -> int:
    api = os.environ.get("API", "http://localhost:8080")
    print(f"[live] {api} 응답 CJK 누수 검사")
    # 한국어 답변을 유도하는 대표 질의들. 실제로는 골든셋에서 끌어와도 됨.
    questions = [
        "안전벨트 경고등은 언제 울리나요?",
        "정기 점검 주기를 알려줘",
        "후방 카메라가 안 나올 때 점검 항목은?",
    ]
    ok = True
    for q in questions:
        try:
            req = urllib.request.Request(
                f"{api}/api/rag/ask",
                data=json.dumps({"question": q, "namespace": "vehicle"}).encode(),
                headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=120) as r:
                ans = json.load(r).get("answer", "")
            ok &= check(q, ans)
        except Exception as e:
            print(f"  ! {q}: 요청 실패 {e}")
            ok = False
    print("PASS ✅" if ok else "FAIL ❌")
    return 0 if ok else 1

if __name__ == "__main__":
    sys.exit(live() if "--live" in sys.argv else self_test())
