# Security (보안)

MiniWatson을 데모에서 운영으로 올릴 때의 위협 모델, 적용한 통제, 잔여 리스크를 기록한다. 핵심 원칙은 **"테넌트 격리는 구조가 아니라 강제(enforcement)로 완성된다"**이다 — namespace로 데이터를 나눠도, 그걸 강제하는 인증/인가가 없으면 보안상 의미가 없다.

## 1. 위협 모델

보호 대상(asset):

- 테넌트별 청크 본문·임베딩(article_vectors, parquet) — 경쟁사·부서 간 유출 시 치명적.
- 감사 로그(query_log) — 질의 내용에 민감정보 가능.
- 시크릿(pg 자격증명, 모델 엔드포인트).

주요 위협(threat):

| # | 위협 | 현재 상태 | 심각도 |
|---|---|---|---|
| T1 | 인증 부재 → 호출자가 `namespace`를 바꿔 **남의 테넌트 데이터 열람** | 무방비 | 치명 |
| T2 | EVAL-ONLY 오버라이드로 외부 요청이 검색 전략 변경 | 노출됨 | 높음 |
| T3 | 시크릿 하드코딩(pg 비번 `miniwatson`) | 코드/yaml에 박힘 | 높음 |
| T4 | 프롬프트 인젝션(검색된 청크가 LLM 지시 오염) | 미대응 | 중 |
| T5 | SQL 인젝션(text-to-SQL) | SELECT 전용 가드 있음 | 낮음(부분 방어) |
| T6 | PII 유출(응답·로그) | PiiRedactionService 부분 커버 | 중 |
| T7 | 전송 평문(TLS 없음), 레이트리밋 부재(DoS) | 인프라 영역 | 중 |

이 문서는 T1(인증+격리)을 1순위로 다룬다 — 가장 치명적이고, 거버넌스(watsonx.governance)의 테넌트 격리 보증과 직결된다.

## 2. 핵심 원칙: authN과 authZ/격리는 분리된다

- **authN(인증)** — "이 호출자가 누구인지" 증명. API key / JWT 등 *방식*은 갈아끼울 수 있다.
- **authZ/격리(인가)** — "이 주체가 이 namespace를 봐도 되나". 주체→허용 namespace 매핑 + 요청 검증.

격리 가드(주체→namespace)는 인증 방식과 **무관한 공통 코어**다. 그래서 코어를 한 번 만들고 인증 어댑터만 바꾸면 여러 인증 방식을 같은 토대에서 비교할 수 있다(Chunker/VectorStore 전략 패턴과 동형).

## 3. 인증 방식 결정 가이드 (3안 비교)

| 축 | A. API key + 경량 필터 | B. Spring Security + API key | C. Spring Security + JWT |
|---|---|---|---|
| 인증 | `X-API-Key` 헤더 → config 맵 | 같은 키, SS 필터체인 | `Bearer <JWT>` 서명·클레임 |
| 의존성 | 없음(OncePerRequestFilter) | starter-security | + oauth2-resource-server |
| 격리 | 필터가 주체→namespace 세팅 + 가드 | SS authorities | JWT namespace claim |
| 토큰 관리 | 키 직접 발급·저장 | 동일 | 발급기(IdP)·회전·JWKS 필요 |
| 복잡도 | 낮음 | 중 | 높음 |
| 배우는 것 | authN/authZ/격리 본질 | 표준 보안 프레임워크 | 엔터프라이즈 토큰 인증 |
| prod/watsonx | 데모·내부 API | 표준 | 현실(IAM Bearer) |
| 약점 | 키 라이프사이클 직접 구현, 비표준 | SS 러닝커브, API key는 SS 관용 밖 | 무겁고 IdP 인프라 전제 |

한 줄 평:

- **A** — 최소 코드로 "인증+격리" 개념을 가장 선명히. 단 키 회전/폐기를 직접 짜야 하고 표준 프레임워크 방어(세션·CSRF·헤더)가 빠진다.
- **B** — Spring Security 필터체인·인증컨텍스트를 제대로 학습. API key는 SS 1급 시민이 아니라 커스텀 필터를 끼워야 해 약간 어색.
- **C** — 실제 엔터프라이즈(watsonx IAM, OAuth2)에 가장 근접, 확장성·표준성 최고. 대신 토큰 발급기·JWKS·클레임 매핑까지라 로컬 학습엔 무겁다.

채택: **격리 코어 공통 → A로 working 확인 → B → C** 순으로 같은 토대에 어댑터를 더한다. 코어가 셋의 90%라, A를 세우면 B·C는 인증 앞단만 교체.

## 4. 격리 코어 (인증 방식 무관 공통)

- `TenantContext` — 요청 단위로 "이 호출자가 접근 가능한 namespace 집합"을 보관(ThreadLocal). 인증 어댑터가 채우고, 가드가 읽는다.
- `TenantGuard.requireAccess(namespace, allowed)` — 요청 namespace가 허용 집합에 있는지 강제. `*`는 전체 권한(dev/admin). 위반 시 `TenantAccessException`(HTTP 403). 순수 함수라 단위 테스트 가능.
- `SecurityProperties` — `security.enabled` + `security.api-keys`(key→CSV namespaces) 바인딩.
- 강제 지점: namespace를 받는 **데이터 경계**(RagService.ask, IngestionService.*)에서 가드를 부른다. 컨트롤러마다 흩뿌리지 않고 choke point에서 한 번 — 새 진입점이 생겨도 데이터 계층에서 막힌다.

```
요청 → [인증 어댑터 A/B/C] → TenantContext.set(allowed)
                              → 서비스 진입 시 TenantGuard.requireAccess(ns, TenantContext.allowed())
                              → 위반: 403 / 미인증: 401(어댑터에서)
```

## 4.1 구현 고려사항 (짚고 갈 것)

설계·구현에서 놓치면 조용히 뚫리는 지점들:

- **강제 위치 = 데이터 경계.** 가드를 컨트롤러마다 흩뿌리지 말고 namespace를 받는 서비스 진입(RagService.ask, IngestionService.*)에서 한 번. 새 엔드포인트가 생겨도 데이터 계층에서 막힌다. 대가: 각 서비스 진입에서 가드 호출을 잊으면 구멍.
- **ThreadLocal 누수.** `TenantContext`는 필터 `finally`에서 반드시 `clear()`. 안 그러면 스레드풀 재사용으로 **앞 요청의 테넌트 권한이 다음 요청에 샌다**(치명적 격리 붕괴).
- **401 vs 403.** 키 없음/틀림 = 401(필터). 인증됐으나 권한 없음 = 403(가드). 그리고 **존재하지 않는 namespace와 권한 없는 namespace를 구분해 노출하지 말 것**(404/403 oracle로 타 테넌트 존재 추론 방지) — 둘 다 403으로.
- **fail-closed.** security.enabled인데 설정이 비거나 깨지면 **거부**(열어주지 말 것). 미인증 컨텍스트(allowed=null)도 거부.
- **상수시간 키 비교.** API key를 `equals`로 비교하면 타이밍 공격 표면. `MessageDigest.isEqual`(상수시간)로.
- **키 저장.** 데모는 config 맵이지만, 운영은 **해시 저장**(평문 금지) + 회전·폐기 절차. 로테이션 없는 정적 키는 유출 시 영구 노출.
- **ingest도 격리.** 질의만이 아니라 적재도 가드 — 테넌트가 남의 namespace에 쓰지 못하게.
- **default namespace 정규화.** blank/null → "default"를 가드와 저장이 **동일하게** 처리(nsKey). 어긋나면 "default만 허용"인 키가 namespace 생략으로 우회 가능.
- **노출 표면 잠금.** 운영에선 /h2-console, /swagger-ui, actuator도 인증/차단(지금은 열려 있음).
- **감사에 주체 연결.** query_log에 "누가"(테넌트/키 id) 기록 — authN을 거버넌스에 연결(T1↔감사 완전성).

## 4.2 설계 결정 (왜 이 구조인가)

격리를 세 컴포넌트로 쪼갰다. 합치면 당장은 짧지만, 인증 방식 교체(A→B→C)와 테스트·운영 토글에서 깨진다. 각 분리의 *왜*:

1. **`TenantContext` (요청 상태 운반) 분리** — 인증 어댑터가 *채우고* 가드가 *읽는* 결합점. 이 인터페이스(ThreadLocal Set<String>)만 고정하면 인증 방식(A/B/C) 누가 와도 뒷단은 무변경. authN과 authZ를 잇는 얇은 계약.
   - *왜 ThreadLocal*: 요청-스레드 단위 상태. 단, 스레드풀 재사용 누수를 막으려 필터 `finally`에서 `clear()` 필수(고려사항 참고).

2. **`TenantGuard` (순수 인가 규칙) 분리** — "이 namespace 봐도 되나"의 규칙만. Spring·DB·HTTP 의존 0이라 **단위테스트로 규칙을 고정**(이미 5케이스: 허용/거부/와일드카드/미인증/blank). 규칙이 한 곳에 있어 변경·감사 쉬움. prefixFor·requireReadOnly와 동일한 "순수 static" 패턴.

3. **`TenantAccessChecker` (운영 게이트) 분리** — `security.enabled` 체크 + 가드 호출. **운영 토글(off면 통과)을 순수 규칙(Guard)에 섞지 않으려고** 한 겹 둔다. 서비스는 이거 하나만 부르면 되고, "보안 끈 dev/eval에선 통과"라는 환경 결정이 규칙과 분리돼 테스트가 안 깨진다.

그리고 두 가지 배치 결정:

4. **authN ↔ authZ 분리** — 필터(ApiKeyAuthFilter)는 "누구냐"(인증)만, 가드는 "권한 되냐"(인가)만. 그래서 인증 방식을 API key→JWT로 바꿔도 **격리 로직 90%가 그대로** 재사용된다. 3안 비교(3절)가 가능한 이유.

5. **강제 지점 = 데이터 경계** — 컨트롤러가 아니라 namespace를 받는 서비스(RagService.ask, IngestionService.*)에서 가드를 부른다. 새 컨트롤러·새 진입점이 생겨도 **데이터 계층에서 한 번 막히면 끝** — 강제점이 흩어지지 않는다(단일 진실). 질의뿐 아니라 적재도 같은 지점에서 막아 "남의 namespace에 쓰기"까지 차단.

요약: **운반(Context) · 규칙(Guard) · 게이트(Checker)를 나누고, 인증과 인가를 분리하고, 데이터 경계에서 강제한다.** 이 셋이 "인증 방식은 갈아끼우되 격리는 불변" + "규칙은 테스트로 고정" + "토글이 규칙을 안 더럽힘"을 동시에 만든다.

## 5. 로드맵

- Tier 1: 인증+격리(T1), EVAL-ONLY 게이팅(T2), 시크릿 외부화(T3).
- Tier 2: 입력검증·프롬프트 인젝션 문서화(T4/T5), PII 커버리지(T6), TLS·레이트리밋(T7).
- Tier 3: 감사 완전성(주체 연결), 의존성 스캔.

## 6. 잔여 리스크 (정직하게)

- A 방식은 키 회전·폐기를 직접 구현해야 하며 표준 프레임워크의 검증된 방어가 빠진다 → 운영은 B/C로.
- 프롬프트 인젝션은 완전 차단이 어렵다(검색 콘텐츠는 신뢰 불가 입력) — 시스템 프롬프트 경계·출력 검증으로 완화.
- 본 문서의 통제는 적용 진행 중이며, 각 항목 구현 시 이 표를 갱신한다.
