# 매뉴얼 메타데이터 1차 필터 (RAG 정확도 단계 1)

> 461권 매뉴얼 KB가 커지면 의미 검색만으론 차종·연식·언어가 섞여 노이즈가 빠르게 늘어난다.
> **메타 1차 필터 → 하이브리드(BM25+벡터) → 리랭커** 3단 계획 중 **1단계** 구현 기록.

## 1. 문제 — 461권 매뉴얼이 한 KB에 섞여 있다

- `data/vehicle/manuals/` 459개 PDF + IA 영문본 2개 = 461권.
- 기존 `Article`은 `title/namespace/url`만 — 차종·연식·언어·구동계 메타가 없음.
- `Chunker`는 `(text, maxSize)`만 받아 구조 무시.
- 결과: "투싼 하이브리드 정기점검" 같은 질의도 코나·아반떼 등 무관 매뉴얼 청크가 후보로 섞임.

## 2. 파일명 표준화

기존: `<CODE>_<YEAR>_ko_KR.pdf` (예: `AX_2025_ko_KR.pdf`) — 차종 정보 없이 프로젝트코드만.

**새 규칙**:
```
hyundai_<year>_<model>[_<powertrain>]_<CODE>_owners_<REGION>.pdf
```
예시:
- `hyundai_2025_casper_AX_owners_KR.pdf`
- `hyundai_2025_avante_hybrid_CN7HEV_owners_KR.pdf`
- `hyundai_2020_ioniq_electric_AEEV_owners_KR.pdf`
- `hyundai_2025_staria_sv_US4SV_owners_KR.pdf`

**근거 매핑 출처** (90 코드 중 89 매핑, 정확도 우선):
- 매니페스트 CSV(`ml/data/owners_manuals_hyundai_kr.csv`) — 현행 45 코드
- PDF 메타데이터 `/Title`로 확정 — 9 코드(county, county_electric, newpower, solati, eleccity_doubledecker, mighty, pavise, porter_est, staria_sv)
- "구동용 고전압" 본문 키워드 → EV 확정 — 1 코드(GYEV)
- 사용자가 현대 공식 포털·웹으로 확정 — 7 코드(blueon, veracruz, avante HEV, porter II, veloster Gen2, maxcruz, i40)
- 단종 모델 통상 표기 — 27 코드(avante AD/MD, sonata LF/YF, grandeur HG/IG 등)

미상 1 코드(`VI`, 2012~2015 4파일)는 `hyundai_2012_vi_VI_owners_KR.pdf` 형태로 code-fallback. 향후 보강 가능.

도구: [`scripts/rename_manuals.py`](../scripts/rename_manuals.py) — dry-run/apply 양방향. `migrate_manual_titles.sql`도 동시 산출(향후 적재된 DB에 재실행 가능).

## 3. Article에 메타 6 필드 추가

```java
// src/main/java/com/miniwatson/data/Article.java
private String carCode;    // 프로젝트코드 (CN7HEV, AX…) — 대문자
private String carModel;   // 로마자 모델 (avante, casper…) — 소문자
private String powertrain; // hybrid|electric|phev|fcev|sv|null
private Integer year;      // 연식
private String lang;       // ko|en
private String region;     // KR|EN|US
```

Avro 스키마([`article.avsc`](../src/main/resources/article.avsc))는 `["null", T]` union + `default: null` — **구 Parquet 파일과 호환**. `ArticleParquetStore`도 `record.getSchema().getField(name) != null` 가드로 NPE-free.

## 4. ManualMeta 파서

[`src/main/java/com/miniwatson/service/ManualMeta.java`](../src/main/java/com/miniwatson/service/ManualMeta.java)

- 입력: 파일명 문자열
- 출력: `ManualMetaResult(code, model, powertrain, year, lang, region)` 또는 `null`
- 로직:
  1. `^hyundai_(\d{4})_(.+?)_owners_([A-Z]{2})\.pdf$` 정규식
  2. 끝 토큰이 대문자/숫자 only → 프로젝트코드
  3. 그 직전 토큰이 `hybrid/electric/phev/fcev/sv` → 파워트레인
  4. 남은 토큰들 = 모델 (`_` join, 소문자)
  5. `lang = KR→ko, EN/US→en`

`ManualMeta.apply(article, filename)` — Article에 메타 주입(파일명 매칭 안 되면 no-op).

## 5. 적재 경로 주입

`IngestionService.ingestText()`에서 청크마다 `ManualMeta.apply(article, baseTitle)` 호출.  
이미지·위키 등 매뉴얼 패턴 아닌 파일은 자동 패스(메타 null 유지).

## 6. 기존 청크 백필 잡

[`BackfillManualMetaRunner`](../src/main/java/com/miniwatson/service/BackfillManualMetaRunner.java) — `CommandLineRunner`, 기동 시 1회.

- 청크 `title`의 접미사(` #N`) 제거 → `ManualMeta.parse` → 메타 주입.
- **idempotent** (이미 채워졌으면 스킵, 재시동 무해)
- 변경분 있을 때만 1회 `saveAll` → ArticleParquetStore가 파티션 시그니처 비교로 디스크 안 건드림.
- 토글: `backfill.manual-meta.enabled=false` (기본 true).
- 로그: `[backfill] manual-meta done — scanned=X updated=Y skipped(already)=A non-manual=B in Z ms`

## 7. RAG 1차 필터(검색 정확도)

`RagService.ask()`에 4개 nullable 파라미터 추가:

```java
public RagResult ask(... String car, Integer year, String lang, String powertrain)
```

- 후보 1차 검색(`fetchN` 6배 확장) → **메타 필터(stream filter)** → 리랭커.
- `car`는 `carModel`("ioniq5") 또는 `carCode`("NE1N")에 부분일치.
- 필터 적중 0이면 경고 로그 + 전체 후보 fallback(검색은 살림).
- title 필터와 직교 — 같이 사용 가능.

REST: `POST /api/rag/ask`
```json
{
  "question": "투싼 하이브리드 정기 점검 주기",
  "namespace": "vehicle",
  "car": "tucson",
  "powertrain": "hybrid",
  "lang": "ko"
}
```

## 8. 검증 — 컴파일 확인 (수동 동작 검증은 다음 단계)

- `./mvnw -q -DskipTests compile` ✓ (이 PR 통합 시점)
- ManualMeta 단위 테스트 / 통합 RAG 호출 검증은 후속 작업.

## 9. 다음 단계 — 2·3단계

이 PR는 **메타 1차 필터(1단계)** 까지. 남은:

- **2단계 — 하이브리드 검색 검증**: BM25(`KeywordIndex`)와 벡터 점수 RRF 머지가 이미 있음(`HybridRetriever`). 메타 필터와 함께 효과 측정 후 가중치 조정.
- **3단계 — 크로스인코더 리랭커**: 메타 + 하이브리드로 top-50 후보 → `bge-reranker-base` 같은 작은 cross-encoder로 top-5. 정확도↑ / 지연 +100~300ms.
- **별도 — 섹션 메타**: 매뉴얼 PDF의 헤딩/TOC를 보고 청크에 `section`(안전/정비/제원/경고등) 라벨. 메타 필터 강화. 헤딩 파싱이 비용이 커 분리.

## 10. 변경 파일 요약

| 파일 | 변경 |
|---|---|
| `src/main/java/com/miniwatson/data/Article.java` | 메타 6필드 |
| `src/main/resources/article.avsc` | union null 6필드 |
| `src/main/java/com/miniwatson/service/ManualMeta.java` | **신규** — 파일명 파서 |
| `src/main/java/com/miniwatson/service/IngestionService.java` | 적재 시 `ManualMeta.apply` |
| `src/main/java/com/miniwatson/data/ArticleParquetStore.java` | 6필드 read/write + 호환 가드 |
| `src/main/java/com/miniwatson/service/RagService.java` | 메타 필터 분기 |
| `src/main/java/com/miniwatson/dto/AskRequest.java` | 4 필드 노출 |
| `src/main/java/com/miniwatson/controller/RagController.java` | 새 필드 전달 |
| `src/main/java/com/miniwatson/service/BackfillManualMetaRunner.java` | **신규** — 기동 시 1회 백필 |
| `scripts/rename_manuals.py` | **신규** — 파일명 표준화 + SQL 산출 |
| `scripts/migrate_manual_titles.sql` | **생성물** — DB 적용용 (적재 후 재실행 가능) |
