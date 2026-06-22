# Vehicle 코퍼스 데이터 소스 (현대차 도메인)

> 원칙: 공식 배포 문서 + 공공데이터만. 공홈 대량 크롤링 (ToS, 저작권, JS렌더).
> 수동 다운로드 + 스크립트 인제스트. 재배포 제한 자료는 로컬 인덱싱만 (`data/vehicle/`는 gitignore).

## 1) 오너스 매뉴얼 / 사용설명서 (A·S와 매뉴얼 RAG 핵심)

| 소스 | URL | 형식 | 비고 |
|---|---|---|---|
| 현대/기아 오너스매뉴얼 포털 | https://oms.hmc.co.kr/ | PDF/웹 | 차종, 연식별 공식 매뉴얼 |
| 현대 디지털 취급설명서 | https://ownersmanual.hyundai.com/ | 웹/PDF | langCode=ko_KR |
| 현대 다운로드센터(사용설명서) | https://www.hyundai.com/kr/ko/download-center.html | PDF | 취급설명서, 자료실 |
| 현대 웹 매뉴얼(인포테인먼트) | http://webmanual.hyundai.com/ | 웹(HTML) | AVN/내비 매뉴얼 |
| 현대모비스 A/S 인포테인먼트 매뉴얼 | https://www.mobis-as.com/multi_prod_manual.do | PDF | 정비/부품 쪽 |

→ 권장: 차종 2~3종(예: 아반떼/쏘나타/아이오닉) 매뉴얼 PDF를 받아 `data/vehicle/manuals/`에 저장.
   기존 PDF/HWP 인제스트 파이프라인이 그대로 처리.

**실제 사용 코퍼스 (재현 가능):** 위 현대 공홈은 로그인·ToS·JS 렌더 제약이 있어, 공개·직접 PDF인 **Internet Archive**로 수집했다(`ml/data/manuals_manifest.csv` → `fetch_manuals.py`).
- 컬렉션: `identifier:car-service-manuals-hyundai*` (총 69개, 연식별)
- URL 패턴: `https://archive.org/download/<식별자>/<식별자>.pdf`
- 수집 차종(모델별 최신 연식): Accent, Sonata, Tucson, Santa Fe, Elantra, Elantra GT, Veloster, **Azera(=그랜저)**, **Equus(=에쿠스)**, Genesis, Genesis Coupe, Sonata Hybrid, Entourage

> **중요 — 매뉴얼 종류:** 컬렉션 이름이 'service-manuals'지만 본문을 확인하면 전부 **OWNER'S MANUAL(오너스 매뉴얼 / 취급설명서)** 이다(운전자용: 작동법·경고등·점검 주기·제원·안전 주의사항). **공장 정비 매뉴얼**(분해·조립·토크·DTC 트러블슈팅·배선도)이 아니다. 후자는 현대차 정비사 전용 시스템(국내 **GSW**, 미국 **hyundaitechinfo.com**)에 잠긴 **유료·비공개 자산**이라 공개 수집이 불가하며, 프로덕션에서는 라이선스 계약으로 붙이는 영역이다. 파일명은 `hyundai_<연식>_<모델>_owners.pdf`로 통일한다.

## 2) 리콜 / 결함(DTC) 공공데이터 (정형 → text-to-SQL + RAG)

| 소스 | URL | 형식 | 비고 |
|---|---|---|---|
| 자동차리콜센터 | https://www.car.go.kr/ | 웹/엑셀 | 국내 리콜 현황 |
| 공공데이터포털 (자동차 리콜) | https://www.data.go.kr/ | CSV/API | "자동차 리콜" 검색 |
| NHTSA (미국 리콜, 불만, DTC) | https://www.nhtsa.gov/ , https://api.nhtsa.gov/ | API/CSV | 영어, 대량 구조화 |

→ CSV는 `data/vehicle/recalls/`에 저장 → DuckDB text-to-SQL(이미 구현)로 집계 질의.

## 3) 정비코드 표준 (OBD-II DTC)

- OBD-II 표준 고장코드(P0xxx/C/B/U) 표 → `automotive-glossary.json` 시드로 사용.
- 출처는 공개 표준 코드 목록(SAE J2012 기반 공개 자료). 코드→정의 매핑만 사용.

## 4) 파인튜닝용 instruction 데이터 (P2)

- 위 1~3 문서에서 LLM으로 Q&A(instruction) 쌍 합성 → `ml/data/build_dataset.py`.
- 형식: `{"instruction","input","output"}` JSONL, train/val 분할.

## 라이선스 메모

- 매뉴얼/카탈로그: 현대차 저작물. **개인 학습과 연구 목적 로컬 인덱싱만**, 재배포와 커밋 금지.
- 공공데이터포털/NHTSA: 공공 오픈 라이선스(출처표기). CSV 커밋 가능 여부는 각 라이선스 확인.
- 레포에는 코드, 스키마, 소량 샘플만. 원문 코퍼스는 `data/vehicle/` (gitignore).
