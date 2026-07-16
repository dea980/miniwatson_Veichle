# RUNBOOK — MCP 서버 실행/연결/검증

> 목적: miniwatson_Veichle의 네이티브 MCP 서버(Spring AI WebMVC)를 띄우고, MCP Inspector로 노출 툴을 검증한 뒤 호스트(Claude Desktop 등)에 연결하는 절차. 데모 재현용.

관련 문서: [ARCH-AGENT-PROTOCOLS.md](ARCH-AGENT-PROTOCOLS.md) (설계/결정)

## 1. 사전 조건

- pom.xml에 spring-ai-bom(dependencyManagement import) + spring-ai-starter-mcp-server-webmvc가 있어야 한다.
- 버전 확인: `./mvnw dependency:resolve` 통과. 실패 시 ARCH 문서 6절(버전 제약)의 대안으로.
- VehicleMcpTools(@Tool 5종)와 McpConfig(ToolCallbackProvider) 컴파일 통과.

## 2. 서버 기동

```
./mvnw spring-boot:run
```

- 기동 로그에서 두 가지를 확인한다: (a) 실제 포트(기본 8080이나 application.yaml에서 바뀔 수 있음), (b) MCP SSE 엔드포인트 경로. Spring AI WebMVC 스타터는 기본 `/sse`(SSE)와 메시지 엔드포인트를 노출하나, 버전에 따라 경로가 다를 수 있으므로 로그로 확정한다.
- 결과 URL 예: `http://localhost:8080/sse`.

## 3. Inspector 실행 및 연결

```
npx @modelcontextprotocol/inspector
```

- 브라우저 UI가 뜨면 Transport를 SSE로 선택하고 2절에서 확인한 SSE URL을 입력해 Connect.
- 로컬 stdio 방식이 아니라 이미 떠 있는 웹 서버에 붙는 구조다(WebMVC 변형이라 그렇다).

## 4. Inspector 기능 맵

| 영역 | 하는 일 | 이 프로젝트에서 |
|---|---|---|
| Transport(연결) | 연결 방식 선택. stdio면 커맨드/인자/env, 네트워크면 URL | SSE + 서버 URL로 연결 |
| Tools 탭 | 노출 툴 목록 + JSON 스키마 + description, 입력 폼 Run, 응답 JSON | 검증 핵심. 5종이 뜨는지/호출되는지 |
| Resources 탭 | 리소스 목록/MIME/내용 읽기/구독 테스트 | 지금은 비어 있는 게 정상(@McpResource 미노출) |
| Prompts 탭 | 프롬프트 템플릿/인자/렌더 미리보기 | 지금은 비어 있는 게 정상(@McpPrompt 미노출) |
| Notifications/Logs | JSON-RPC 요청/응답, 진행 알림, 서버 로그 실시간 | 버그 추적. DevTools Network 탭 격 |

## 5. 검증 시나리오 (스모크 테스트)

1. Tools 탭에 5종이 보이는지: recall_check, ask_manual, fleet_overview, similar_cases, diagnose.
2. 각 툴의 description과 파라미터 스키마가 의도대로 렌더되는지(호출 에이전트가 보는 프롬프트가 이 텍스트다).
3. recall_check 호출: model=PALISADE(연식 생략) Run → recalls 배열과 count 반환 확인.
4. fleet_overview 호출: period=week Run → totals가 전체(period=all)보다 작은지(기간 필터 동작 확인).
5. ask_manual 호출: 짧은 질문 Run → answer와 sources 반환 확인(LLM 경로라 수 초 소요).
6. Notifications 탭에서 각 호출의 JSON-RPC 요청/응답이 오가는지 확인.

통과 기준: 5종 노출 + recall_check/fleet_overview가 결정적 데이터 반환 + ask_manual이 근거 포함 답변 반환.

## 6. CLI 모드 (자동 검증)

Inspector는 UI 외에 CLI 모드가 있어 스크립트/CI에서 툴 호출을 자동 검증할 수 있다. 정확한 플래그는 실행 시 `--help`로 확인한다. 회귀 테스트로 엮으면 데모 전에 5종이 살아 있는지 한 줄로 점검 가능하다.

## 7. 트러블슈팅

- Inspector가 연결 안 됨: 서버 포트/SSE 경로를 기동 로그로 재확인. 방화벽/CORS보다 경로 오타가 흔하다.
- Tools 탭이 빔: McpConfig의 ToolCallbackProvider 빈이 등록됐는지, VehicleMcpTools가 @Service로 스캔되는지 확인.
- 툴은 뜨는데 호출 실패: Notifications 탭의 원본 에러 확인. 대개 위임 서비스(예: ensure/DuckDB 로드)나 파라미터 타입 불일치.
- 버전/의존성 에러: ARCH 문서 6절(Spring AI x Boot) 대안.

## 8. 확장 여지

- 매뉴얼 KB를 @McpResource로 노출하면 Resources 탭이 채워진다.
- 진단용 프롬프트 템플릿을 @McpPrompt로 노출하면 Prompts 탭이 채워진다.
- 거버넌스 훅: 각 @Tool 진입부에서 GovernanceService의 audit/PII 마스킹을 통과시키면, 어떤 호스트가 호출해도 감사/마스킹이 걸린다(MVP 다음 1순위).

## 참고

- MCP Inspector — https://modelcontextprotocol.io/docs/tools/inspector
- modelcontextprotocol/inspector (GitHub) — https://github.com/modelcontextprotocol/inspector
