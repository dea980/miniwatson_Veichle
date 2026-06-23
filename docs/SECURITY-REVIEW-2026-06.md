# 보안 리뷰 보고서 — Tabular SQL 경로 (DuckDB)

- **대상**: `miniwatson_Veichle` 브랜치 (vehicle 도메인 RAG 플랫폼)
- **리뷰 일자**: 2026-06-24
- **리뷰 범위**: 본 브랜치에서 신규/변경된 코드의 보안 영향 (기존 이슈 제외)
- **방법**: 식별 → 병렬 false-positive 필터 → 신뢰도 8/10 미만 제거 → 핵심 파일 직접 코드 확인
- **결과**: HIGH 2건 확정. 신규 변경 파일(타이틀·메타 스코프 RAG, `ManualMeta`, `BackfillManualMetaRunner`, `ArticleParquetStore`)에서 추가 취약점 없음.

---

## 요약

| # | 심각도 | 분류 | 위치 | 신뢰도 |
|---|--------|------|------|--------|
| 1 | HIGH | Path Traversal / 임의 파일 읽기 | `TabularController.java:32` → `TabularSqlService.java:35` | 9/10 |
| 2 | HIGH | SQL 가드 우회 / 임의 파일 읽기 | `TabularSqlService.java:130` (`requireReadOnly`) | 8/10 |

두 건 모두 **DuckDB의 파일시스템 읽기 기능이 신뢰 불가 입력에 노출**된다는 동일한 근본 원인을 공유한다. 벡터 1은 `/load`의 `path` 파라미터로 직접, 벡터 2는 `/ask`의 LLM 생성 SQL로 간접적으로 도달한다.

---

## Vuln 1 — Path Traversal / 임의 파일 읽기

- **심각도**: HIGH
- **분류**: `path_traversal`
- **신뢰도**: 9/10
- **위치**:
  - `src/main/java/com/miniwatson/controller/TabularController.java:32-38`
  - `src/main/java/com/miniwatson/service/TabularSqlService.java:35-43`

### 설명
`POST /api/tabular/load`는 `@RequestParam String path`를 검증 없이 `registerCsv(table, path)`로 전달하고, 이 값은 DuckDB 쿼리에 문자열로 삽입된다.

```java
// TabularSqlService.java:37-42
String p = path.replace("'", "''");   // 작은따옴표만 escape
st.execute("CREATE OR REPLACE TABLE " + t +
           " AS SELECT * FROM read_csv_auto('" + p + "', normalize_names=true)");
```

작은따옴표 escape는 SQL 문자열 탈출만 막을 뿐, **경로 탐색은 막지 못한다.** 디렉터리 허용목록(allowlist)이나 정규화(canonicalization)가 호출 경로 어디에도 없다. DuckDB `read_csv_auto()`는 로컬 임의 경로를 읽는다.

### 익스플로잇 시나리오
1. `POST /api/tabular/load?table=x&path=/etc/passwd` → 파일이 DuckDB 테이블 `x`로 적재됨
2. `POST /api/tabular/ask {"table":"x","question":"모든 행을 보여줘"}` → 파일 내용이 JSON `rows`로 응답에 노출
3. 서버가 읽을 수 있는 모든 파일(설정, 키, `/etc/passwd` 등) 유출 가능

### 권고 수정
`path`를 정규화하고 허용 디렉터리 내부로 제한한다.

```java
Path resolved = Path.of(path).toAbsolutePath().normalize();
Path allowed  = Path.of("data").toAbsolutePath().normalize();
if (!resolved.startsWith(allowed))
    throw new IllegalArgumentException("path must be within data/");
sqlService.registerCsv(table, resolved.toString());
```

> 참고: `/upload`(멀티파트) 경로는 `getOriginalFilename()`을 `[^a-zA-Z0-9._-]→_`로 새니타이즈하고 `createTempFile`을 쓰므로 안전. 문제는 `/load`의 서버 경로 직접 지정뿐.

---

## Vuln 2 — SQL 가드 우회 (DuckDB 파일시스템 함수)

- **심각도**: HIGH
- **분류**: `sql_injection` / 임의 파일 읽기
- **신뢰도**: 8/10
- **위치**:
  - `src/main/java/com/miniwatson/service/TabularSqlService.java:130-140` (`requireReadOnly`)
  - `src/main/java/com/miniwatson/service/TabularSqlService.java:145-166` (`runSelect`)
  - 도달 경로: `TabularController.java:67-69` (`/api/tabular/ask`) → `TextToSqlService.ask(...)`

### 설명
`requireReadOnly()`는 `SELECT`/`WITH` 시작 + DML/DDL 키워드 차단목록으로 읽기 전용을 강제한다.

```java
// TabularSqlService.java:130-140
if (!(up.startsWith("SELECT") || up.startsWith("WITH")))
    throw new IllegalArgumentException("SELECT/WITH 쿼리만 허용: " + sql);
for (String bad : List.of("DROP","DELETE","UPDATE","INSERT","ALTER",
                          "CREATE","ATTACH","COPY","PRAGMA","INSTALL","LOAD"))
    if (up.contains(bad)) throw new IllegalArgumentException("금지 키워드: " + bad);
```

차단목록에 **DuckDB 읽기측 파일시스템 함수**(`read_csv_auto`, `read_text`, `read_blob`, `glob`)가 없다. 이 함수들은 `SELECT` 안에서 유효하며 로컬 임의 파일을 읽는다. `/api/tabular/ask`는 사용자 `question`을 LLM에 줘 SQL을 생성하고, 그 SQL을 테이블 허용목록 없이 `runSelect()`로 실행한다.

### 익스플로잇 시나리오
1. 공격자가 LLM을 유도하는 `question` 제출
2. LLM이 `SELECT * FROM read_csv_auto('/etc/passwd')` 생성
3. `SELECT`로 시작하고 금지 키워드가 없어 `requireReadOnly()` 통과 → DuckDB 실행 → 파일 내용 응답
4. 2회 self-correction 루프가 유효 SQL 생성 성공률을 높임

### 권고 수정
파일시스템·네트워크 함수를 차단목록에 추가하고, 가능하면 외부 접근 자체를 끈다.

```java
for (String fn : List.of("READ_CSV","READ_PARQUET","READ_JSON","READ_TEXT",
                         "READ_BLOB","GLOB","HTTPFS","HTTP_GET","HTTP_POST"))
    if (up.contains(fn)) throw new IllegalArgumentException("Blocked function: " + fn);
```

- 더 강하게: 생성된 SQL이 **등록된 테이블명만** 참조하는지 검증
- DuckDB 연결에 `SET enable_external_access=false` 적용(로드 경로가 필요로 하지 않는 경우)

---

## 검토했으나 제외한 후보 (false positive / 범위 외)

| 후보 | 위치 | 판정 | 사유 |
|------|------|------|------|
| 레거시 정적 UI XSS | `static/js/app.js` (innerHTML) | 제외 (2/10) | 데이터 소스(위키 REST·PDF 추출 텍스트)가 HTML 주입 불가, 기존 코드 패턴 |
| 인증 기본 off | `application.yaml:83` | 제외 (2/10) | `application-prod.yaml`이 `SECURITY_ENABLED:true`로 운영 기본 on, compose가 prod 프로필 활성 |
| 에러 메시지 노출 | `application.yaml:30` | 제외 | 시크릿·PII 미포함, 하드닝 항목(룰 제외) |
| pgvector 기본 크리덴셜 | `application.yaml:64-65` | 제외 (2/10) | env 오버라이드, "디스크 저장 시크릿" 제외 룰 |
| API 키 평문 저장 | `ApiKeyAuthFilter.java` | 제외 (3/10) | 상수시간 비교 사용, "디스크 저장 시크릿" 제외 룰 |
| 타이틀·메타 스코프 RAG | `RagService.java:120-152` | 안전 | 인메모리 Java 스트림 비교(`equalsIgnoreCase`/`contains`), SQL 미사용 |
| 파티션 파일명 생성 | `ArticleParquetStore.java:62-66` | 안전 | `[^a-zA-Z0-9._-]→_` 새니타이즈 + 길이 제한 + 해시, 경로 구분자 미보존 |

---

## 우선순위 / 조치 제안

1. **즉시(HIGH)** — `requireReadOnly()`에 DuckDB 파일시스템 함수 차단 추가 (Vuln 2). LLM이 유도되면 임의 서버 파일을 읽을 수 있음.
2. **즉시(HIGH)** — `TabularController.load()`의 `path`를 `data/` 하위로 검증 (Vuln 1).
3. **방어 심화** — 가능하면 DuckDB `enable_external_access=false`, 등록 테이블명 화이트리스트로 SQL 검증.
