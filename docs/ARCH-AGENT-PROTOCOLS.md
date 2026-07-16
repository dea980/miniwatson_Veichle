# 에이전트 프로토콜 아키텍처 (RAG / MCP / A2A)

> 요지: RAG, MCP, A2A는 경쟁하는 선택지가 아니라 서로 다른 층위다. RAG는 지식(안), MCP는 능력 노출(밖에서 안으로 호출), A2A는 에이전트 간 위임(피어). miniwatson_Veichle은 RAG 코어 위에 MCP로 능력을 표준화하고, 멀티 에이전트가 실제로 필요한 지점에만 A2A를 붙인다. 모든 툴 호출은 거버넌스 게이트를 통과한다.

## 0. 왜 MCP를 달았는가 (동기·대안·결정)

**문제**: RAG/집계/진단 능력을 REST로만 노출하면, 이를 쓰려는 LLM 에이전트나 데스크톱 호스트마다 1:1 커스텀 통합이 필요하다. 게다가 REST 엔드포인트는 사람/코드가 명시적으로 호출해야 하며, LLM이 "언제 이 기능을 써야 하는가"를 스스로 판단하지 못한다.

**대안 검토**:
- REST 그대로 둔다: 가장 단순하지만 호스트마다 통합을 다시 짜야 하고 LLM 자율 호출이 안 된다. 에이전트가 붙는 시나리오엔 부족.
- 별도 어댑터 프로세스(Python/Node가 REST를 래핑): 빠르게 되지만 프로세스가 둘로 늘고 HTTP 홉이 생기며 거버넌스를 어댑터에서 다시 태워야 한다.
- 네이티브 MCP 서버를 앱에 내장(Spring AI WebMVC): 단일 배포물, 서비스 직접 호출, 거버넌스 인프로세스. → 채택.

**결정: 네이티브 MCP 서버를 앱에 내장.** 근거:
1. LLM이 툴 description을 보고 **자율 호출**한다. 사람이 URL을 짚지 않아도 "팰리세이드 리콜?" 한마디에 recall_check를 부른다.
2. **한 번 노출하면 모든 MCP 호스트**(Claude Desktop, IDE, 타 에이전트)가 코드 수정 없이 쓴다. 1:N 통합이 1:표준으로 바뀐다.
3. 호스트 LLM이 5개 툴을 **멀티스텝으로 조합**한다(핫스팟→규제확인→진단). 하드코딩하지 않은 워크플로도 성립.
4. **로컬 유지 = 데이터 주권.** 민감 데이터를 클라우드로 내보내지 않고, 호출이 GovernanceService(audit/PII)를 인프로세스로 통과한다. 엔터프라이즈(오토에버)가 원하는 신뢰 경계.
5. REST와 **같은 서비스 계층을 재사용** → 로직 중복 0. 창구만 하나 더 낸 것.

**트레이드오프/비용**: Spring AI 의존성 추가, 툴 스키마와 description 유지 부담, 대량 결과는 반환 압축 필요(§8 구현 노트). 앱을 end-to-end로 통제하고 외부 LLM 호스트가 붙을 일이 없다면 MCP는 오버킬이며 그때는 REST가 낫다. 이 프로젝트는 채용 직무가 에이전트 프로토콜을 요구하고, 로컬 데이터에 에이전트를 붙이는 그림 자체가 목표라 값어치가 있다.

---

## 1. 세 층위 정의

| 층위 | 한 줄 정의 | 방향 | 관계 |
|---|---|---|---|
| RAG | 시스템이 무엇을 아는가 (지식/근거) | 내부 | 능력의 토대 (프로토콜 아님) |
| MCP | 내 능력을 표준 툴로 밖에 연다 | 호스트 to 서버 | "너는 내 도구다" (클라이언트-서버) |
| A2A | 다른 자율 에이전트와 동료로 일한다 | 에이전트 to 에이전트 | "우리 같이 일한다" (피어 위임) |

핵심 구분: MCP는 툴 접근이고 A2A는 에이전트 협업이다. 같은 층이 아니므로 둘 중 하나를 고르는 문제가 아니라, 각각 다른 필요를 푼다.

## 2. 어디에 앉는가 (컴포넌트 매핑)

```
[MCP 호스트: Claude Desktop/IDE]   [다른 에이전트: 타 팀/서비스]
            | MCP                            | A2A
            v                                v
  ┌──────────────────── miniwatson_Veichle 서비스 ────────────────────┐
  |  MCP 서버(노출, 툴 5종)        A2A 엔드포인트(선택, 위임)          |
  |                    \            /                                  |
  |             Agent 오케스트레이션 (AgentService: RAG/SQL 판단)      |
  |             ── 거버넌스 게이트: audit + PII 마스킹 통과 ──         |
  |             RAG 코어(RagService, 리랭커)   데이터(DuckDB, 매뉴얼 KB)|
  └───────────────────────────────────────────────────────────────────┘
```

- RAG 코어는 그대로 지식 토대. 나머지 전부가 이 위에 앉는다.
- MCP 서버는 기존 서비스(AgentService, RagService, AnalyticsService)를 툴로 감싸 밖에 노출한다. 컨트롤러가 아니라 서비스에 붙인다(컨트롤러는 HTTP 어댑터일 뿐).
- A2A 엔드포인트는 점선. 실제 두 번째 에이전트가 있을 때만 의미가 있다(4절 참조).
- 거버넌스 게이트: 어떤 경로로 들어오든 툴 호출은 기존 GovernanceService의 audit/PII 마스킹을 통과한다. 엔터프라이즈 채택의 전제.

## 3. 언제 무엇을 쓰는가 (결정)

- RAG: 손대지 않고 코어로 유지. 모든 응답의 근거.
- MCP: 지금의 주력. AgentService/RagService/AnalyticsService를 툴로 노출해 어떤 호스트든 붙는 능력을 만든다. 노력 대비 임팩트 최대이며, 채용 우대사항의 "에이전트 프로토콜 활용" 항목을 정확히 채운다.
- A2A: 단일 서비스에서 억지로 넣으면 과장으로 보인다. 진짜 두 번째 에이전트가 있을 때만 붙인다.

## 4. A2A에 대한 정직한 스탠스

단일 서비스 포트폴리오에서 A2A는 데모용 장식이 되기 쉽다. 리뷰어가 엔지니어라 근거 없는 프로토콜 채택은 간파한다.

붙일 거라면 최소한의 정직한 시나리오는 하나다: 현재 diagnose(진단) 하나를 두 에이전트로 분리한다. "정비 진단 에이전트"가 "부품 견적/재고 에이전트"에게 A2A로 위임하는 구조. 이때 비로소 프로토콜이 필요한 이유가 생기고 데모가 성립한다. 그 두 번째 에이전트를 실제로 만들 각오가 서기 전에는 A2A를 스킵하고 MCP와 거버넌스에 집중한다.

## 5. MCP 구현 계획

접근: 네이티브 Spring AI MCP 서버(WebMVC 변형)를 기존 앱 안에 둔다. 별도 어댑터 프로세스 대신 단일 배포물로 가서, 거버넌스/audit가 같은 프로세스에 걸리게 한다.

노출 툴 5종(기존 서비스 위임):

| 툴 | 위임 대상 | 용도 |
|---|---|---|
| recall_check(model, modelYear) | AnalyticsService.recallCheck | 차종/연식 미조치 리콜 조회 |
| ask_manual(question) | RagService.ask | 매뉴얼 KB 자연어 질의(출처 포함) |
| fleet_overview(period) | AnalyticsService.overview | 기간별 플릿 품질 집계 |
| similar_cases(caseId, k) | AnalyticsService.similarCases | 과거 유사 증상 top-k |
| diagnose(question) | AgentService.run | RAG/SQL 조합 진단 에이전트 |

설계 원칙:
- 툴은 서비스에 붙인다(컨트롤러 아님).
- 툴 description이 곧 호출 에이전트가 보는 프롬프트다. 언제 쓰는 툴인지와 입출력을 정확히 적는다.
- 반환은 압축한다. 거대한 raw JSON 대신 모델이 읽기 좋은 요약.
- 트랜스포트는 stdio부터(로컬 데모, Claude Desktop), 네트워크 서비스면 HTTP/SSE.

## 6. 버전 제약 (Spring AI x Spring Boot)

- 현재 Boot 4.0.6, Java 21.
- Spring AI 1.1.x가 Boot 4.0.x와 맞는 조합(검증 사례: Boot 4.0.3 + Spring AI 1.1.3). 리스크 최소 경로.
- Spring AI 2.0 GA는 Boot 4.1을 요구. 2.0으로 가려면 parent 버전을 4.1로 올려야 하며 다른 의존성 영향 검토 필요.
- MCP 서버 스타터엔 version을 쓰지 않고 spring-ai-bom으로 버전을 관리한다(dependencyManagement import).

## 7. 채용 서사 연결

"RAG로 근거를 만들고, 그 위 에이전트를 MCP로 표준 툴화해 어떤 호스트든 붙게 했다. 멀티 에이전트가 실제로 필요한 지점(진단에서 부품견적)만 A2A로 위임했고, 모든 툴 호출은 거버넌스(audit/PII)를 통과한다."

이 문장이 우대사항의 "A2A, MCP 등 에이전트 프로토콜 활용 에이전트 개발"을 정직하게 닫는다. 지식그래프/온톨로지 항목은 도메인(차종-리콜-부품-증상)을 온톨로지로 명시화하는 별도 트랙에서 다룬다.

## 8. 현황과 다음 액션

- [x] pom.xml: spring-ai-bom(dependencyManagement import) + spring-ai-starter-mcp-server-webmvc, spring-ai.version=1.1.3
- [x] dependency:resolve 통과 (Spring AI 1.1.3 x Boot 4.0.6 정상)
- [x] VehicleMcpTools: @Tool 5종 작성(서비스 위임, String 반환, snake_case 이름)
- [x] McpConfig: MethodToolCallbackProvider로 툴 등록
- [x] MCP Inspector(CLI)로 검증: /sse 200, recall_check(model=PALISADE) → 리콜 26건 정상 반환
- [ ] Claude Desktop/Cowork에 연결(실제 호스트 데모)
- [ ] 거버넌스 훅: 각 @Tool 진입부에서 audit/PII 통과(MVP 다음 1순위)
- [~] 반환 압축: recall_check는 캠페인 기준 dedup 완료(26행→12건, 연식은 years 배열로). 나머지 대량 툴은 필요 시 동일 패턴 적용
- [ ] (선택) A2A: 진단에서 부품견적 위임하는 두 번째 에이전트가 생길 때만

### 구현 노트 (밟은 함정)

- ObjectMapper: Boot 4에서 이 앱 컨텍스트에 ObjectMapper 빈이 없어 생성자 주입이 실패했다. 툴 내부에서 `new ObjectMapper()`로 로컬 생성해 해결(빈 의존 제거).
- 툴 이름: @Tool에 name을 안 주면 메서드 이름(camelCase)이 툴 이름이 된다. 문서/관례와 맞추려면 `@Tool(name = "recall_check")`처럼 snake_case를 명시한다.
- 반환 형식: @Tool이 Map/객체를 반환하면 이 버전에서 MCP content 블록이 클라이언트 스키마와 어긋날 수 있다. String(JSON)을 반환하면 텍스트 블록으로 깔끔히 감싸진다.
- 로컬 검증은 UI Inspector의 OAuth/프록시 토큰 실랑이를 피해 CLI 모드(`--cli ... --method tools/call`)가 빠르다.
- 데이터 형태 주의: recalls는 (캠페인 x 연식) 행이라 그대로 반환하면 count가 부풀고 중복된다. 툴 레벨에서 캠페인 기준으로 묶고 연식은 years 배열로 접어 count를 실제 리콜 건수로 맞춘다(REST/UI는 안 건드림).
