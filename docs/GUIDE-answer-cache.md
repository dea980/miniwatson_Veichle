# 구현 가이드 — RAG 답변 캐시 (하드닝 Step A)

> **구현 완료 (2026-06-25)** — 파일:
> - `src/main/java/com/miniwatson/service/RagCacheService.java` (신규, 캐시 래퍼)
> - `src/main/java/com/miniwatson/controller/RagController.java` (`/ask`가 `askCached` 경유)
> - KB 버전 = `DocumentCatalogRepository.count()`(문서 수, 모드 무관). 캐시 store = `GeneratedReport` type="ASK".
> 남은 것: **백엔드 재빌드 → k6로 before/after 측정**(아래 §검증). 컴파일·검증은 운전자(나=daeyeop) 몫.

**목표**: 같은 (질문·네임스페이스·모델·필터)면 LLM 재호출 없이 **즉시 응답**. 기준선의
`/ask p95 57s`를 캐시 히트 시 **웹 레이어 수준(수십 ms)**으로. = "성능 + 데이터 정합성" 한 번에 보여주는 자료.

**패턴**: `ReportService.caseSummary`와 동일한 **compute-once + 영속 캐시**(`GeneratedReport`). 재사용.

---

## 설계 (왜 이렇게)
- **캐시 위치 = 별도 래퍼** `RagCacheService` (RagService는 순수 유지 = 관심사 분리). 컨트롤러가 래퍼를 호출.
- **키** = `sha256(question | namespace | model | car | year | kbVersion)`.
  - `kbVersion` = **KB 상태 토큰**(예: `article_vectors` 행수). **적재가 늘면 행수↑ → 키 변경 → 자동 무효화.**
    → "캐시 정합성 어떻게?"의 답: *캐시를 KB 상태에 종속시킨다*. (면접 포인트)
- **저장 페이로드** = 경량 JSON `{answer, sources:[{title,summary,url}]}`. **임베딩 절대 저장 금지**
  (`Article.embedding`은 `@JsonProperty(WRITE_ONLY)`라 직렬화서 빠지지만, 명시적으로 경량 DTO 권장).
- **반환** = 히트면 경량 sources로 `Article`을 **최소 필드만** 채워 `RagResult` 재구성(임베딩 null OK — 표출엔 title/summary/url만 씀).

## 단계 (네가 구현)
**1) `RagCacheService` 새 파일** (`service/RagCacheService.java`)
- 주입: `RagService rag`, `GeneratedReportRepository reportRepo`, `PgVectorStore`(또는 카운트 제공자),
  `ObjectMapper mapper`(jsr310 등록).
- 메서드: `RagResult askCached(question, ns, model, title, car, year, lang, powertrain, force)`.

**2) 키 빌더**
```java
long kbVersion = pgVectorStore.count();           // 이미 COUNT(*) 메서드 있음 (없으면 추가)
String raw = String.join("|", q, ns, nz(model), nz(car), str(year), Long.toString(kbVersion));
String key = sha256(raw);                          // java.security.MessageDigest
```

**3) 조회 → 히트 반환**
```java
var hit = reportRepo.findFirstByReportTypeAndReportKeyOrderByCreatedAtDesc("ASK", key);
if (!force && hit.isPresent()) {
    var dto = mapper.readValue(hit.get().getContentJson(), CachedAnswer.class);
    return new RagService.RagResult(dto.answer, toLightArticles(dto.sources), null);  // cached
}
```

**4) 미스 → 1회 생성 → 저장**
```java
RagService.RagResult r = rag.ask(q, ns, model, null, null, title, car, year, lang, powertrain);
CachedAnswer dto = new CachedAnswer(r.answer(),
    r.sources().stream().map(a -> new Src(a.getTitle(), trim(a.getSummary(),600), a.getUrl())).toList());
GeneratedReport gr = hit.orElseGet(GeneratedReport::new);
gr.setReportType("ASK"); gr.setReportKey(key); gr.setModel(model);
gr.setContentJson(mapper.writeValueAsString(dto)); gr.setCreatedAt(LocalDateTime.now());
reportRepo.save(gr);
return r;   // 첫 호출은 원본 그대로
```
(`CachedAnswer`/`Src`는 작은 record 2개. `toLightArticles`는 Src→Article(title/summary/url만 set).)

**5) 컨트롤러 배선**: `RagController.ask`가 `ragService.ask(...)` 대신 `ragCacheService.askCached(...)` 호출.
내부 호출자(ReportService 진단 등)는 **그대로 RagService.ask** 직접 — 캐시는 사용자 /ask 경로만.

## 함정 체크리스트
- [ ] 임베딩 직렬화 금지(경량 DTO). [ ] 키에 kbVersion 포함(무효화). [ ] LLM 비결정성 — temp 낮으면 안정,
      허용 가능. [ ] force=true로 강제 재생성 경로(디버그). [ ] 캐시 미스/파싱 실패 시 안전 폴백(원본 ask).

## 검증 (before/after)
1. 같은 질문 2회 호출 → 1차 ~45s, **2차 수십 ms**(로그/타이머).
2. `k6 run loadtest/rag_ask_load.js` (질문 3개 고정셋) → 워밍 후 재실행 → **p95 57s → 웹 수준**으로 급락.
   `ROADMAP-backend-hardening.md §0.5`의 before와 같은 표에 after 추가.
3. 적재 1권 더 → `kbVersion` 변경 → 같은 질문이 **다시 미스**(무효화 동작 확인). = 정합성 증명.

## 면접 한 줄
"반복 RAG 질의를 KB 상태(행수)에 키를 종속시킨 영속 캐시로 처리해 p95를 57s→수십 ms로 줄였고, 적재가
바뀌면 키가 바뀌어 자동 무효화되도록 정합성을 보장했다."

## 막히면
파일·증상(컴파일 에러/캐시 안 먹음) 던지면 그 부분만 같이 디버그. 코드는 네 손으로(학습 목표).
2단 캐시로 더 가려면(인메모리 Caffeine 앞단) Step5에서 `@Cacheable` 추가 — 단 임베딩 안 담기 동일 주의.
