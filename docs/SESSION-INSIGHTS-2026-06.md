# 작업 인사이트 — 케이스 중심 재구성 · 데이터 재수집 · 매뉴얼 확장 · RAG 스케일 (2026-06)

이 문서는 이 세션에서 내린 결정과 그 **근거(왜)**, 그리고 남은 과제를 맥락별로 정리한다.
면접/리뷰용 — "무엇을 했나"보다 "왜 그렇게 했나"에 초점.

---

## 1. MVP 스파인: 접수번호(케이스)가 별, 차종은 카테고리

**결정**: 진단 리포트의 단위를 **차종 → 접수번호(odiNumber)**로 옮겼다. 차종은 케이스를 빠르게
찾는 **카테고리(namespace 같은 역할)**로 격하.

**왜**:
- A/S 현장의 작업 단위는 "이 차 한 대(이 접수 건)"지 "팰리세이드 전체"가 아니다.
- 차종 종합 리포트는 LLM 종합 서술에 매번 ~30초가 걸렸고(아래 §5), 정작 정비사에겐 개별 진단이 필요.
- 그래서 차종 화면은 전부 **결정적 SQL 집계**(리콜·불만·점검표·부품빈도)만 — LLM/RAG 제거 → 즉시 응답.
- 서술형 진단(RAG)·견적·점검·정비사 메모는 **접수번호 리포트**로 이관.

**구현**: `ReportService.buildCarReport`에서 RAG·LLM 호출 삭제(결정적 한 줄 요약만).
`caseReport(접수번호)`가 AI 진단(RAG) + 견적 + 점검 + 정비사 메모를 **스냅샷으로 적재**.

---

## 2. 비싼 LLM 산출물은 적재(영속 캐시)한다

**결정**: 생성된 리포트를 `generated_report` 테이블(JPA)에 **JSON 스냅샷 + 생성일**로 저장.
캐시 우선 반환, `force=true`일 때만 재생성(정비사 메모는 보존).

**왜**: 리포트 생성 = RAG + LLM으로 매번 수십 초·고부하. "매번 생성"이 가장 큰 체감 문제였다.
1회 생성 후 적재 → 두 번째부터 즉시. "생성일" 표시로 신뢰성/감사 추적도 확보.

**부가**: 정비사가 직접 쓴 **메모**도 같은 스냅샷에 병합 적재 → AI 제안 + 사람 소견이 함께 문서화.

---

## 3. 해결 상태도 영속 — localStorage → DB

**결정**: 케이스 "해결 처리"를 `resolved_case` 테이블에 저장. `cases()`가 `NOT IN`으로 제외.

**왜**: localStorage는 기기·세션마다 달라 운영에서 일관성이 없다. "해결하면 큐에서 사라짐"은
공유 상태여야 한다. 되돌리기(unresolve)·해결 내역 조회도 추가.

---

## 4. 케이스 큐: 서버 페이지네이션 + 우선순위 가시화

**결정**: `OFFSET/LIMIT` 서버 페이지네이션(1·2·3…), 정렬 토글(전체 심각도순 / 차종별),
우선순위 공식을 화면에 명시.

- **우선순위 = 사망×100 + 부상×10 + 화재×5 + 사고×3** (높을수록 시급).
- `ORDER BY priority DESC, date DESC, odinumber` — **odinumber 타이브레이커**로 페이지 경계 안정화
  (타이브레이커 없으면 동일 우선순위가 페이지마다 흔들림).

---

## 5. "리포트 500"의 진짜 원인 — 토큰이 아니라 재생성 부하

**가설(틀림)**: 토큰/청크 사이즈 초과.
**실측(로그)**: RAG 증강 프롬프트가 **1,327자**뿐 — 컨텍스트 한계 근처도 아님.
**진짜 원인**:
- 리포트 1건 = RAG(~15s) + LLM 종합(~15s), 매번 ~30초·Ollama CPU 점유.
- 동일 리포트가 **두 번 발사**(StrictMode/더블클릭)되고, `HikariPool thread starvation`까지 동반.

**교훈**: 증상("500")을 보고 원인("토큰")을 단정하지 말 것. 로그로 프롬프트 길이를 측정해 가설 기각.
**조치**: 적재 캐시(§2)로 재생성 제거 + `num_ctx=8192` 명시(미설정 시 모델 기본 2048급이라 장기적 보험).

---

## 6. NHTSA 불만 데이터: summary가 원본에서 500자 cap

**발견**: 케이스 상세 "접수 내용"이 문장 중간에서 끊김. 원인은 프론트/SQL이 아니라 **데이터**:
복정 1429건 전부 summary **max 500자**(781건이 정확히 500). 수집 시점에 truncate된 것.

**조치**: NHTSA 공개 API(`complaintsByVehicle`)에서 full summary로 **재수집 스크립트**
(`scripts/refetch_nhtsa_complaints.py`). 모델 목록은 추측 말고 `products/vehicle/models`로
**연식별 자동 조회**(전기/하이브리드 변형까지 정확히). macOS SSL 이슈는 certifi 폴백으로 처리.
리콜 데이터는 cap 없음(max 428/188/686) — 리콜 상세는 그대로 full.

---

## 7. 오너스 매뉴얼(한국어) 확장 — 포털 내부 API 역추적

**문제**: 기존 KB 매뉴얼은 2016 영어·내연뿐. 불만 데이터는 2020~2026·전기/하이브리드까지인데
**진단 근거(매뉴얼) 공백**. archive.org는 2016에서 끊기고 EV 없음. 미국 영어 PDF는 직접 URL 있으나
한국어 출력 목표와 안 맞음.

**해결(Claude in Chrome)**: 한국 디지털 취급설명서(`ownersmanual.hyundai.com`, JS SPA)에서
- 카탈로그 API `/api/v2/hmc/models` → 모델·`projCode`
- 상세 API `/api/v2/hmc/model/owners-manuals?projectCode&year&langCode&countryCode` → `omManual.pdfManual`
- **PDF 직접 URL 패턴 확정**: `ownersmanual.hyundai.com/full_pdf/{projectCode}/{year}/ko_KR` (쿼리 없는 정적 PDF)

전 라인업 **37종**(아이오닉 5/6/9·NEXO·코나EV·캐스퍼EV + 하이브리드/PHEV + 신형 내연/N) URL 확보.
매니페스트(`ml/data/owners_manuals_hyundai_kr.csv`) + 다운로드·인제스트 스크립트
(`scripts/fetch_ingest_kr_manuals.py`, SSL 폴백·중복스킵·`--models/--limit`).

**교훈**: JS SPA는 web_fetch로 껍데기만 → 내부 API를 네트워크/번들에서 역추적하면 깔끔한
정적 엔드포인트가 나온다(파라미터명은 `projCode`가 아니라 `projectCode`였던 것처럼 추측 금지, 실측).

---

## 8. RAG ingest 스케일 — 현재 형태와 병목

**현재(동기 파이프라인)**: `POST /api/data/ingest-file` → Tika 전체 추출 → recursive 청킹(1000자)
→ 청크마다 ①`granite-embedding:278m` 임베딩 1콜 ②H2 저장 ③벡터인덱스 `add`. dedup은 `loadAll()`.
기본 `vector.store=memory`, dev DB=H2 in-memory(create-drop).

**37권 병목**:
1. 청크당 임베딩 1콜 순차 → 수만 콜.
2. 한 PDF가 HTTP 요청에 동기로 묶임 → 타임아웃.
3. H2 in-memory + create-drop → 재시작마다 증발·재적재, 힙 폭증.
4. `loadAll()` dedup = O(N) → 누적 O(N²).
5. memory 벡터스토어 → N↑ 시 검색 저하.

**방안(우선순위)**: ①영속 스토어(`VECTOR_STORE=pgvector` + Postgres, 1회 적재 후 유지)
②선별 적재(케이스/EV 공백 모델만) ③비동기 잡+진행률 ④임베딩 병렬/배치
⑤dedup을 `DocumentCatalog` 존재검사로 ⑥매뉴얼용 청크 상향(1500~2000)+문서당 상한.

**이번 라운드 적용**:
- ⑤ **dedup을 카탈로그 기반 O(1)** 로 교체(`IngestionService`: `loadAll()` 스캔 제거).
- ⑥ **청크 max-size 1000→1400** (매뉴얼 청크 수↓; RAG는 소스당 600자 표출이라 품질 영향 미미).
- **콜드 스토어 = 문서별 파티션 Parquet** (`ArticleParquetStore`): 단일 `articles.parquet` 전체
  재작성 → `./data/articles/` 디렉터리에 문서(namespace+제목)별 파일. saveAll이 **시그니처가 바뀐
  파티션만 재작성**, 사라진 파티션은 삭제, 레거시 단일 파일은 자동 분할. = "PDF별 여러 parquet".
- **nextId 카운터 캐시**(`TieredArticleStore`): 청크당 `loadAll()`(O(N²)) 제거.
- ⓛ 영속(pgvector/H2-file)은 프로파일/환경변수로 운영(아래).

**저장 레이어 인사이트(왜 파티션인가)**: 기존 콜드는 LSM식 tiering(hot=JSON append, cold=단일 parquet).
단일 parquet은 Parquet의 append 불가 특성상 **매 compact마다 전체 삭제·재작성** → KB가 커질수록
재작성 비용이 누적. 문서별 파티션으로 나누면 (a) 새 매뉴얼 = 파일 1개만 생성 (b) 문서 삭제 = 파일 삭제
(c) 병렬 읽기·파티션 프루닝 가능. Parquet의 "불변 파일 + 파티션" 관용구에 정합.

**영속 실행(권장)**: dev는 H2 in-memory+create-drop이라 재시작 시 증발. 매뉴얼을 한 번만 적재하려면
`--spring.profiles.active=demo`(H2 file) 또는 prod+`VECTOR_STORE=pgvector`로 기동.
문서 파티션 Parquet은 그와 별개로 `./data/articles/`에 디스크 영속.

---

## 9. 오케스트레이션(DAG) — 로드맵

ingest를 download→extract→chunk→embed→upsert **단계 DAG**로 만드는 건 정석(재시도·단계 병렬·멱등·관측).
다만 현 규모(37권, 단일 Ollama)에 **Airflow 풀스택은 오버킬**(스케줄러·웹서버·메타DB 운영 부담),
그리고 DAG는 오케스트레이션 껍데기라 병목(임베딩 처리량·영속화)을 직접 풀지 않음.

**접근**: 먼저 ingest를 **멱등 단계**로 구조화(카탈로그 스킵 + 임베딩 병렬 풀) — 이미 절반 적용(dedup·파티션).
포트폴리오/JD용 오케스트레이션 데모가 필요하면 **Prefect/Dagster**(Airflow보다 경량)로 동일 단계
함수를 래핑. 단계: `fetch_manifest → download_pdf → extract_text → chunk → embed(batch) → upsert(partition)`,
각 단계 idempotent + 재시도. → 로드맵(필요 시 착수).

---

## 부록: 운영에서 배운 것
- DuckDB 단일 커넥션 동시성 → 동기화 + register-once + 무효화.
- `QUALIFY row_number()`로 피드 중복(같은 캠페인 다중 행) 제거.
- 정책 제약(샌드박스 HTTP 차단)은 **사용자 머신 실행 스크립트**로 우회: NHTSA·매뉴얼 재수집 모두 동일 패턴.
