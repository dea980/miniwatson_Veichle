# 보안(Security) 검증 세션 기록 — 2026-07-03

이 문서는 "보안 파트를 테스트해보자"는 요청에 대해 **무엇을·왜·어떻게** 했는지를 단계별로 남긴다. 재현과 인수인계용.

---

## 0. 목표

`security.enabled=true`일 때 MiniWatson의 API 인증·격리가 실제로 동작하는지 **관찰로 검증**한다. 코드 리뷰가 아니라 "돌려서 확인".

검증 대상(코드):
- `ApiKeyAuthFilter` — `X-API-Key` 헤더 → 허용 namespace, 없으면 401(fail-closed)
- `SecurityConfig` — 3개 모드(`apikey-filter`(기본)·`spring-apikey`·`jwt`)
- `TenantGuard`/`TenantAccessChecker` — namespace 격리
- 설정: `application.yaml`의 `security.*` (기본 `enabled:false`, 키 `dev-all:"*"`, `acme:"default,kr-bcg"`)

---

## 1. 사전 조사 (왜: 뭘 켜고 뭘 때려야 하는지 알아야 함)

```bash
grep -n -A15 "security" src/main/resources/application.yaml   # 설정 키·기본값 확인
ls src/main/java/com/miniwatson/security/                     # 필터/모드 클래스 목록
find src/test -iname "*ecurity*" -o -iname "*Tenant*" ...     # 이미 있는 테스트 확인
```

확인 결과:
- 토글 env: `SECURITY_ENABLED`, `SECURITY_MODE`(기본 `apikey-filter`)
- 보호 범위: `/api/**`만 (actuator·비-API 경로는 필터 우회 — `shouldNotFilter`)
- 인증 실패 정책: **fail-closed 401** (키 없음/오답/빈 헤더 모두 차단)
- 키 비교: **상수시간**(`MessageDigest.isEqual`) — 타이밍 사이드채널 방지
- 기존 테스트: `TenantGuardTest`(격리 단위테스트)

---

## 2. 라이브 서버로 인증 시나리오 테스트 시도 (1차)

**왜**: 실제 HTTP 요청에 401/통과가 나오는지 보려고.

```bash
# 포트 확인 → 안 떠있음
lsof -i :8080 -sTCP:LISTEN

# 보안 켜고 기동 (백그라운드)
SECURITY_ENABLED=true ./mvnw -q spring-boot:run > /tmp/mw-sec-test.log 2>&1 &

# 헬스가 200 될 때까지 대기 (최대 ~2분)
for i in $(seq 1 40); do
  code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/health --max-time 2)
  [ "$code" = "200" ] && break; sleep 3
done
```

기동은 성공(`Started MiniwatsonApplication in 52s`, health 200 확인).

이어서 인증 시나리오를 때렸다:

| # | 요청 | 기대 |
|---|---|---|
| 1 | 키 없이 `POST /api/tabular/ask` | 401 |
| 2 | `X-API-Key: wrong-key` | 401 |
| 3 | `X-API-Key: dev-all` (유효) | 401 아님(통과) |
| 4 | 빈 헤더 `X-API-Key;` | 401 |
| 5 | `GET /actuator/health` (필터 우회) | 200 |
| 6 | `GET /` (비-API) | non-401 |

**결과: 전부 `000`(연결 실패).** 서버가 죽어 있었다.

---

## 3. 크래시 원인 규명 (왜: 000이 보안 문제인지 환경 문제인지 갈라야 함)

```bash
tail -40 /tmp/mw-sec-test.log
```

로그 마지막:
```
... Tomcat started on port 8080 ... Started MiniwatsonApplication in 52.408 seconds
... DispatcherServlet 'dispatcherServlet' 초기화 (첫 요청 = 내 health curl)
[ERROR] ... spring-boot-maven-plugin:run ... Process terminated with exit code: 134
```

`exit 134 = 128 + 6 = SIGABRT`. 즉 **첫 요청 처리 직후 JVM 네이티브 크래시**. 내 health 200은 죽기 직전 짧은 창에 걸린 것이고, 이후 curl은 죽은 서버라 000.

환경/JVM 확인:
```bash
java -version          # Temurin-21.0.7 (HotSpot) — OpenJ9 아님
ls -t hs_err_pid*.log  # → hs_err_pid9600.log (HotSpot 크래시 덤프)
grep -iE "SIGSEGV|Problematic frame" hs_err_pid9600.log
```

덤프 헤더:
```
SIGSEGV (0xb) ... pid=9600
Problematic frame:
V  [libjvm.dylib+0x975b15]  resource_allocate_bytes(...)+0x15
Java VM: ... mixed mode, emulated-client, ... bsd-amd64
```

**판정 — 보안 버그 아님, 런타임 크래시.**
- `resource_allocate_bytes` 크래시는 JVM 내부 메모리 할당 프레임. 보안 필터 코드와 무관.
- `bsd-amd64` + `emulated-client`: **Apple Silicon(arm64)에서 x86_64 JVM을 Rosetta로 에뮬레이션** 중이라는 신호. 이 환경이 불안정. (참고: `/usr/libexec/java_home -V`에 arm64 JDK가 따로 있음 — SDKMAN current가 x86_64를 가리킴)
- `docs/HOTSPOT-RUNTIME.md`가 기록한 네이티브 크래시 계열과 같은 성격(요청 처리 중 SIGSEGV).

→ 라이브 서버 경로는 **이 맥 로컬 환경의 JVM 아키텍처 문제**로 신뢰할 수 없음. 보안 로직 검증을 여기에 의존하면 안 됨.

---

## 4. 보안 로직 직접 검증으로 전환 (2차 — 이게 통과한 경로)

**왜**: 라이브 서버가 환경 탓에 flaky하니, 보안 규칙 자체를 결정적으로 검증하는 단위테스트로 간다.

```bash
./mvnw -Dtest='*Tenant*,*ApiKey*,*Security*,*Jwt*' test
```

**결과: `Tests run: 5, Failures: 0, Errors: 0` — BUILD SUCCESS** (`TenantGuardTest`).

`TenantGuardTest`가 실제로 검증하는 격리 규칙:

| 테스트 | 규칙 |
|---|---|
| `allowsNamespaceInAllowedSet` | 허용 집합에 있는 namespace는 통과 |
| `deniesNamespaceNotInAllowedSet` | 허용 집합에 없으면 `TenantAccessException` |
| `wildcardAllowsAny` | `"*"`(=`dev-all`)는 아무 namespace나 허용 |
| `nullOrEmptyAllowedIsDenied` | 허용 집합이 null/빈 set이면 거부 (**fail-closed**) |
| `blankNamespaceFallsBackToDefault` | 빈 namespace는 `default`로 폴백, 그 외엔 거부 |

이로써 **격리 정책의 핵심 불변식(fail-closed, wildcard, default 폴백)이 결정적으로 검증됨.**

---

## 5. 결론

| 항목 | 상태 | 근거 |
|---|---|---|
| 격리 규칙(`TenantGuard`) | ✅ 검증됨 | 단위테스트 5/5 통과 |
| 인증 필터 401/통과 | ✅ 검증됨 | `ApiKeyAuthFilterTest` 7/7 통과(§6) — standalone MockMvc, 크래시 회피 |
| 인증 필터 **라이브 HTTP** | ⚠️ 미검증 | 라이브 서버가 로컬 JVM 크래시(SIGSEGV, 환경)로 응답 못 함 |
| 크래시 성격 | 환경 문제 | x86_64 JVM Rosetta 에뮬레이션, 보안코드 무관 |

**남은 일 (실제 HTTP 소켓까지 확인하려면 — 선택):**
1. arm64 네이티브 JDK로 실행 — `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-23.jdk/Contents/Home`(또는 arm64 21) 지정 후 §2 시나리오 재실행.
2. 또는 컨테이너로 실행 — `Dockerfile`은 `eclipse-temurin:21-jre`(HotSpot arm64) 기반이라 이 크래시를 회피. `docker compose up` 후 §2 curl 재실행.

> 필터 로직 자체(401/통과/우회/토글)는 §6에서 이미 결정적으로 닫혔다. 위 1·2는 "실제 TCP 소켓 + 서블릿 컨테이너"까지 포함한 e2e 확인이 필요할 때만.

---

## 6. 인증 필터 인프로세스 검증 추가 (3차 — 라이브 대체, 통과)

**왜**: 라이브 서버는 로컬 x86_64 JVM 크래시로 flaky. 필터의 401/통과 규칙을 전체 컨텍스트 없이 결정적으로 닫는다.

**어떻게**: 전체 `@SpringBootTest`(무거움+크래시 위험) 대신 **standalone MockMvc**로 `ApiKeyAuthFilter`만 격리. 필터는 경로 prefix `/api/`만 보므로 `SecurityProperties`를 손으로 구성하고 더미 컨트롤러(`/api/probe`, `/public`)에 필터를 얹었다. → 파케이/벡터인덱스 로드 없음, 1.8초, 크래시 없음.

파일: `src/test/java/com/miniwatson/security/ApiKeyAuthFilterTest.java`

```bash
./mvnw -Dtest='ApiKeyAuthFilterTest' test
# → Tests run: 7, Failures: 0, Errors: 0 — BUILD SUCCESS (1.8s)
```

| 테스트 | 시나리오 | 기대 |
|---|---|---|
| `noKey_isUnauthorized` | 키 없이 `/api/probe` | 401 |
| `wrongKey_isUnauthorized` | 모르는 키 | 401 |
| `blankKey_isUnauthorized` | 공백 헤더 | 401 |
| `validWildcardKey_passes` | `dev-all`(`"*"`) | 200 |
| `validTenantKey_passes` | `acme`(테넌트 키) | 200 |
| `nonApiPath_bypassesFilter` | `/public` 키 없이 | 200(우회) |
| `securityDisabled_bypassesFilter` | `enabled=false` | 200(토글 off) |

→ **fail-closed 401 · wildcard/테넌트 키 통과 · 경로 우회 · enabled 토글**이 모두 검증됨.

---

## 부록 — 재현 명령 모음

```bash
# 보안 단위테스트 (통과 확인된 경로)
./mvnw -Dtest='*Tenant*,*ApiKey*,*Security*,*Jwt*' test

# 라이브 인증 테스트 (arm64 JDK 또는 컨테이너에서)
export JAVA_HOME=$(/usr/libexec/java_home -a arm64 -v 21 2>/dev/null || /usr/libexec/java_home -a arm64)
SECURITY_ENABLED=true ./mvnw spring-boot:run &
B=http://localhost:8080
curl -s -o /dev/null -w "no-key:%{http_code}\n"   -X POST $B/api/tabular/ask -H 'Content-Type: application/json' -d '{"table":"recalls","question":"x"}'   # 401
curl -s -o /dev/null -w "bad-key:%{http_code}\n"  -X POST $B/api/tabular/ask -H 'X-API-Key: wrong' -H 'Content-Type: application/json' -d '{}'          # 401
curl -s -o /dev/null -w "ok-key:%{http_code}\n"   -X POST $B/api/tabular/ask -H 'X-API-Key: dev-all' -H 'Content-Type: application/json' -d '{}'        # not 401
curl -s -o /dev/null -w "actuator:%{http_code}\n" $B/actuator/health                                                                                    # 200
```
