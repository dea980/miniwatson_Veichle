# Ingest 병목 정리 — 측정·진단·해결

대량 매뉴얼(현대차 한·미 owners manual ~수백 권) 적재 중 발생한 병목과
해결 과정을 한 자리에 모은다. 코드 변경은 단일 브랜치에서 누적되었고,
이 문서는 **무엇이 느렸는가 / 왜 / 어떻게 풀었는가 / 다음은**을 그대로
복기할 수 있게 한다.

대상 커밋(작업 브랜치):
- `src/main/java/com/miniwatson/data/ArticleParquetStore.java`
- `src/main/java/com/miniwatson/data/TieredArticleStore.java`
- `src/main/java/com/miniwatson/service/IngestionService.java`
- `src/main/resources/application.yaml`
- `frontend/components/KnowledgeBasePanel.tsx`

---

## TL;DR

| # | 병목 | 누적 비용 | 해결 | 효과 |
|---|---|---|---|---|
| 1 | 단일 `articles.parquet` 재작성 | O(N²) 디스크 IO | 문서별 파티션 + 시그니처 dirty-only 쓰기 | 변경된 문서 파일만 재작성 |
| 2 | `nextId = max(loadAll())` per save | O(N²) 메모리/IO | 메모리 `maxId` 카운터, 부팅 1회 시드 | save 당 O(1) |
| 3 | 중복 검사 `loadAll()` 풀스캔 | O(N²) | `DocumentCatalog` 존재검사 | save 당 O(1) DB hit |
| 4 | 청크 크기 1000 | 청크 수↑ → 임베딩 호출↑ | 1400으로 상향 | 임베딩 호출 ~30%↓ |
| 5 | 한국 라인업 분류 누락 | 분류 정확도↓ (병목 아님, 부수) | MODELS·정규식 확장 | UI 그룹핑 정확 |
| 6 | (가시성) 단계별 비용 불명 | 의사결정 근거 부재 | `ingestText`에 stage-timing 로그 | 다음 병목 결정 가능 |

---

## 1) 단일 Parquet 파일 재작성

**증상.** 매뉴얼 1권(수십\~수백 청크)을 새로 적재할 때마다
`./data/articles.parquet`이 통째로 다시 쓰여졌다. 누적 청크가 늘수록
한 권 추가에 드는 시간이 선형으로 증가 → 전체 적재가 O(N²).

**원인.** Parquet은 in-place append 불가. 기존 구현은
`saveAll(전체)` → 파일 삭제 → 전량 재기록 패턴이었다.

**해결.** `ArticleParquetStore`를 디렉터리 파티션 스토어로 전환.

- 파티션 키 = `namespace || title(베이스 — '#N' 접미사 제거)` — **문서 단위**.
- 저장 시:
  1. 입력 청크를 파티션별로 그룹핑.
  2. 각 파티션의 **시그니처(정렬된 id 콤마 문자열)** 를 직전 저장본과 비교.
  3. **바뀐 파티션만** 재작성, 동일하면 스킵.
  4. 입력에 없는 파티션 파일은 삭제(전체 상태 동기화 — `compact`/`delete`
     계약 유지).
- 레거시 단일 파일은 최초 로드 시 함께 읽어 다음 saveAll에서 자동 분할.

**효과.** 새 매뉴얼 1권 추가 시 그 문서 파티션 파일 **하나만** 새로 생긴다.
N권 적재의 디스크 IO가 O(N²) → O(N).

**검증 방법.** 적재 후 `./data/articles/` 에 문서당 파일이 1개씩 생기고
타임스탬프가 마지막 변경 시에만 갱신되는지 확인.

---

## 2) `nextId = max(loadAll())` per save

**증상.** `TieredArticleStore.save`에서 매 호출마다 `loadAll()`로 전체
Article을 메모리에 올린 뒤 `max(id)+1`. 청크 1개 저장에 O(N).
대량 적재 누적 O(N²).

**원인.** 단일 `articles.parquet` 시절에는 `loadAll`이 캐시로 싸졌어도
스트림 순회는 매번 일어났음. 파티션화 이후엔 더 큰 문제가 될 수 있어 선제 정리.

**해결.** `TieredArticleStore`에 `private long maxId = -1;` 도입.

- 부팅(또는 첫 save) 시 1회만 `loadAll().mapToLong(Article::getId).max()`로 시드.
- 이후엔 `++maxId`로 발급. `synchronized save`로 경합 차단.

**효과.** save당 비용 O(N) → O(1). 메모리 풋프린트는 long 하나.

**유의.** 외부 프로세스가 동일 저장소에 동시에 쓰지 않는다는 전제. 멀티
인스턴스로 가면 DB 시퀀스/UUID로 교체 필요.

---

## 3) 중복 검사 `loadAll()` 풀스캔 (IngestionService)

**증상.** `ingestText`에서 "이미 적재된 문서면 재삽입 안 함" 로직이
`articleStore.loadAll()`을 받아 전체 Article을 순회해 title 매칭.
청크 N개당 한 번씩 전체 N을 본다 → O(N²).

**원인.** Tier 스토어가 권위 있는 카탈로그가 아니어서 제목 매칭을 본문
저장소에 의존.

**해결.** `DocumentCatalog`(JPA)를 권위로 사용.

- 부팅 시 `hydrateCatalog()`로 기존 본문에서 카탈로그 백필(존재 보장).
- `catalogRepo.findByTitleAndNamespace(baseTitle, ns).isPresent()` —
  인덱스된 쿼리, O(log N) DB hit.
- 일치하면 즉시 `List.of()` 반환(빈 리스트 == 재삽입 없음).

**효과.** 적재 시 ‘이미 있음' 판정이 O(1)에 가깝게. 첫 번째 청크에서 끝남.

**부작용 제거.** 과거엔 "이미 있는 청크들 그대로 반환"이라 호출부가
재삽입과 동일하게 보였는데, 카탈로그 기반이라 책임 분리가 깔끔해짐.

---

## 4) 청킹 크기 1000 → 1400 (`application.yaml`)

**증상.** 매뉴얼 PDF 한 권에서 청크 수가 과다 → 임베딩 RPC 호출 수가
선형으로 늘어 ingest 전체 시간을 좌우.

**해결.** `chunking.max-size: 1000 → 1400`.

**근거.** RAG는 소스당 ~600자만 표출. 1400자 청크여도 컨텍스트 절단
체감 거의 없음. 청크 수 약 30% 감소 → 임베딩 호출도 약 30% 감소.

**검증.** `embed` 단계 누적 ms가 비례 감소하는지 새 timing 로그로 확인(다음 항목).

**되돌릴 신호.** 검색 품질 회귀(소스 추적 누락, 답변 단절). 그땐
`docs/CHUNKING.md`의 A/B 절차로 비교.

---

## 5) 분류 정확도 — 한국 라인업 보강 (frontend)

**증상.** 프론트 `KnowledgeBasePanel`에서 모델명 분류가 `PALISADE`/`SONATA`
같은 영문 글로벌 라인업 위주 → IONIQ/CASPER/STARIA/AVANTE 등 한국 라인업이
"기타"로 빠짐.

**해결.**
- `MODELS` 배열에 한국 라인업(`IONIQ5/6/9/NEXO/CASPER/...`) 추가.
  - **순서 주의:** `IONIQ5`를 `IONIQ`보다 앞에 둬 더 구체적인 매치 우선.
- `categoryOf` 정규식에 한국 라인업 토큰 추가 → "매뉴얼" 카테고리 정확도↑.
- `archiveUrl`: `_kr|owners_kr` 패턴은 archive.org 매핑이 없으므로 `null` 반환.

**효과.** UI 그룹핑/원본 링크가 한국 라인업에서도 일관되게 동작.

**기능적 병목은 아님** — 이 항목은 사용자 가시 데이터 품질 개선.

---

## 6) 가시성 — `ingestText` 단계별 timing 로그

다음 병목이 어디인지 결정하려면 **각 단계의 누적 ms**가 필요했다.
APM/메트릭 도입 없이 한 줄 로그로 충분히 진단 가능.

**구성.** `ingestText`에 stage timer 추가:

```
[ingest-timing] <filename> total=Xms
  extract=Ams        ← Tika/HWP/PDF 텍스트 추출
  dedup=Bms          ← catalog 존재검사
  chunk=Cms (N chunks)
  embed=Dms (avg=D/N ms/chunk)   ← Ollama 임베딩 RPC 누적
  save=Ems           ← TieredArticleStore.save (JSON append + 임계치 도달 시 compact)
  index=Fms          ← IndexingService.index (키워드/벡터)
```

**사용법.** 매뉴얼 1\~2권 적재 후 stdout(또는 로그 파일)에서
`grep ingest-timing`. 비율을 보고 다음 결정:

- `embed`가 70%+ → **(a) 임베딩 배치/병렬화** 또는 **모델 양자화/로컬화**.
- `save`가 30%+ → 파티션 쓰기 비용 — 더 큰 batch 또는 staging buffer.
- `index`가 30%+ → 벡터 인덱스 후보(pgvector/Qdrant) 외부화.
- `extract`가 50%+ → PDF 파싱 파이프라인(스트리밍/페이지 병렬) 검토.

**왜 직접 측정인가.** 짐작으로 비동기 워커부터 도입하면 임베딩이 진짜 병목인 경우
**대기열만 옮긴 셈**이 된다. 측정 → 결정 → 한 단계만 바꾸기.

---

## 로드맵 — 저장소 다중 파일화 (확정 순서)

세 옵션 모두 결국 가야 하지만, **비용 작은 것부터** 단계적으로 간다.
각 단계는 다음 단계의 발판이 되도록 인터페이스(ArticleRepository)를 깨지 않는다.

### Phase 1 — hot 저장소 JSONL 분할 (확정, 진행)

- 현 상태: `data/articles.json` 단일 파일에 모든 hot 청크. save마다 전체
  재기록 → cold와 동일한 O(N²) 누적.
- 변경: `data/articles_hot/<safe(ns||baseTitle)>__<hash>.jsonl` —
  **문서당 파일, 한 줄 = 한 청크**. save = 단일 라인 append → O(1).
- compact 동작: TieredArticleStore가 hot.loadAll → cold.saveAll → hot.saveAll([])
  로 비움. 새 hot도 이 계약 유지(빈 리스트 → 디렉터리 비우기).
- 마이그레이션: 부팅 시 레거시 `data/articles.json`이 있으면 자동으로
  분할 적재 후 옛 파일 삭제(cold의 LEGACY_PATH 패턴과 동일).
- 호출부 변경 0 — `ArticleStore` 컴포넌트 내부 구현만 교체.
- 효과: 매 청크 save가 진짜로 cheap append. compact가 와도 cold가 이미
  파티션이라 dirty-only.

### Phase 2 — PDF 사전 조각 (보류, §6 timing 결과로 트리거)

`[ingest-timing]` 로그에서 `embed` 또는 `extract` 비율이 임계(예: 50%↑)면
페이지 또는 의미 단위(섹션)로 사전 분할. 그래야 임베딩 병렬화·재적재
최소화가 의미를 갖는다. 의미 단위가 안전(페이지 경계는 청크를 깨므로).

### Phase 3 — 외부 DB로 책임 분리 (장기, 임계 N 도달 시)

총 청크 N과 동시 사용자 트래픽이 한계에 닿으면 카탈로그/본문/벡터를
Postgres + pgvector로 옮긴다. Phase 1·2를 거친 시점이면 본문이 이미
문서 단위 파일이라 dump→로드가 단순.

### 트리거(언제 다음 Phase로 가나)

- → Phase 2: ingest 1회 timing에서 `embed`+`extract`가 전체 70%↑ **또는**
  매뉴얼 100권 적재가 1시간 이상.
- → Phase 3: 총 청크 100k↑ **또는** 멀티 인스턴스(읽기 확장) 필요 시점.

---

## 다음 단계 (현재 보류, 측정 결과로 결정)

브레인스토밍 메모만 — 아직 구현 금지. 측정 데이터 확보 후 1개만 채택.

1. **임베딩 비동기 워커** — ingest 요청은 청크 메타만 enqueue, 워커가
   배치 임베딩. Spring `@Async` + 작업 테이블이 가장 가벼움.
2. **벡터 외부화 (pgvector / Qdrant)** — embedding 컬럼을 Parquet에서 분리.
   검색 latency 안정화·운영 모니터링 가능. 운영 복잡도↑.
3. **PDF 파싱 병렬화** — 페이지 단위 split → 병렬 OCR/Tika.
4. **임베딩 배치 API** — Ollama 모델별로 batch 지원 여부에 따라.
5. **incremental 인덱스** — 파티션별 키워드 인덱스 파일을 따로 두고
   변경 파티션만 재구축.

선택 기준 = **timing 로그에서 가장 큰 박스부터.**

---

## 부록 — 변경 전/후 비교 (의사 코드)

### Parquet 저장

```text
Before:
  saveAll(List<Article> all):
    delete  ./data/articles.parquet
    write   ./data/articles.parquet   ← 전량 재기록

After:
  saveAll(List<Article> all):
    group by (namespace, baseTitle)
    for each partition:
      if signature(ids) unchanged: skip
      else rewrite ./data/articles/<safe_key>__<hash>.parquet
    delete partitions absent from input
```

### nextId

```text
Before:  nextId = loadAll().stream().mapToLong(getId).max()+1   // O(N)
After:   if (maxId < 0) maxId = seed(); a.setId(++maxId);       // O(1)
```

### Dedup

```text
Before:  loadAll().anyMatch(a -> sameNs && sameBaseTitle)       // O(N)
After:   catalogRepo.findByTitleAndNamespace(t, ns).isPresent() // O(log N) indexed
```

---

문서는 코드와 함께 갱신한다. 다음 측정 결과가 나오면 §6 아래에
실제 비율을 붙이고, 채택된 다음 단계의 PR 링크를 §"다음 단계"에 연결.

---

## 7) 중복 ingest 진단에서 얻은 인사이트 (2026-06-24)

사용자 의심: "같은 데이터를 다른/같은 파일명으로 여러 번 ingest한 것 같다."
빠른 진단 스크립트(`scripts/scan_duplicates.py`)로 본문 저장소를 훑은 결과
다음 사실을 확인했다.

### 발견된 상태(현재 디스크)

- `data/articles.json` 36KB, 단 3 행 — id 값은 **4992**. 즉 과거 4992개
  청크가 발급된 적이 있는 상태.
- `data/articles.parquet` **0 바이트** — 콜드 본문이 비어 있음.
- `data/articles/` (신 파티션 디렉터리) 비어 있음 — saveAll 미실행.
- `data/miniwatson.mv.db` 832KB — H2 권위 DB(카탈로그 포함) 살아 있음.
- `data/vehicle/_manuals_dups/` 디렉터리 존재 — 과거에 누군가/무엇이
  중복 매뉴얼을 격리한 흔적.
- `data/vehicle/manuals/` 463개 파일.

### 해석

1. **본문(article) 저장소와 카탈로그가 비대칭**: id 시퀀스는 4992까지
   올라간 적이 있는데 현재 hot에 3행, cold는 0바이트. 본문은 사라졌거나
   별도 위치로 옮겨졌고, 카탈로그(H2)에만 이력이 남아 있을 가능성.
   → **권위 카탈로그와 본문의 정합성 점검 루틴이 필요**(부팅 시
   `hydrateCatalog`는 본문→카탈로그 방향만 채움; 역방향 검증 없음).
2. **본문 단일 Parquet 시절의 흔적**: 0바이트 `articles.parquet`는 과거
   "삭제 후 재기록" 패턴 도중 실패/취소한 상태일 가능성. 단일 파일
   재작성이 원자성을 보장하지 못함 — §1에서 해결한 파티션 구조가 이런
   사고를 구조적으로 막음.
3. **`_manuals_dups`의 존재** = 파일 레벨에서 이미 중복이 발생했었다는
   증거. dedup이 ingest 단(§3 catalog 기반)에서만 동작하므로, **파일
   업로드 전 단계(파일명·콘텐츠 해시)에서도** 1차 필터가 있으면 안전.

### 후속 액션 후보 (구현 보류 — 의사결정 필요)

- (i) **catalog ↔ 본문 정합성 검증 작업** — 부팅 시 또는 관리 엔드포인트로
  `DocumentCatalog.title`이 본문 파티션에 실제 존재하는지 양방향 확인,
  결손 카탈로그는 격리/소프트삭제.
- (ii) **파일 단계 dedup**: 업로드 시 SHA-1(content) → 이미 등록된 해시면
  거부. catalog에 `content_hash` 컬럼 추가.
- (iii) **`_manuals_dups` 폴더 처리 결정**: 보존 / 삭제 / git-ignored.
  현재는 어떤 규칙으로 만들어졌는지 미상 → README 1줄 보강 필요.

### 사용 방법 — 진단 스크립트

```
python3 scripts/scan_duplicates.py --top 20
```

- (A) 같은 baseTitle, 같은 청크 인덱스(`#N`)가 두 번 이상 등장 → 같은
  파일을 두 번 ingest한 흔적.
- (B) summary 텍스트의 SHA-1 충돌 → 다른 파일명, 같은 내용.

본 진단은 본문 저장소만 본다(카탈로그는 별도). H2 카탈로그 dump는 후속
작업으로 분리(서버 미가동 시 `org.h2.tools.Script`로 SQL 덤프 가능).

---

## 8) 실측 1회 — IONIQ5 KR 매뉴얼 (2026-06-24)

Phase 1 적용 후 첫 실 적재. 테스트 파일:
`data/vehicle/manuals/hyundai_2025_ioniq5_NE1_owners_KR.pdf` (366 chunks).

```
[ingest-timing] hyundai_2025_ioniq5_NE1_owners_KR.pdf total=154509ms
  extract=54367ms (35.2%) ← Tika PDF 추출 (이미지 다수·한국어)
  dedup=     67ms (<0.1%) ← catalog O(1)
  chunk=    119ms (<0.1%)
  embed= 82148ms (53.2%) ← Ollama 임베딩, 224ms/chunk × 366 직렬
  save=  15830ms (10.2%) ← 임계치 100, compact 3~4회 발동
  index=    212ms (<0.2%)
```

### 해석

- **embed + extract = 88%** → Phase 2 트리거 조건(>70%) 충족.
- save 10%는 compact 임계치를 100→500으로 올리면 한 권 안에서 compact가
  덜 발동돼 즉시 줄어든다(저비용 큰 효과).
- dedup/chunk/index 합 1% 미만 — 신경 X.

### 부수 사고 — Spring DevTools 자동 재시작

ingest 중 코드 변경(또는 classpath touch)을 devtools가 감지하면 컨텍스트가
재시작되어 **응답이 끊긴다**(`Empty reply from server`). 데이터는 부분
적재될 수 있어 더 큰 문제.

조치: `application.yaml` 에 `spring.devtools.restart.enabled: false` 추가.
일반 개발로 복귀하면 true로. 또는 환경변수 `SPRING_DEVTOOLS_RESTART_ENABLED=false`로
프로세스 단위만 끄는 것도 가능.

근본 해결: 응답을 HTTP 연결 수명에 묶지 않는 **비동기 응답(202 Accepted +
작업 ID)** — Phase 3 직전에 별도 작업으로 다루는 게 자연스럽다.

### 즉시 행동 (비용 작은 순)

1. `storage.tier.threshold: 100 → 500` — yaml 한 줄. save 15s → 3s 기대.
2. **embed 병렬화** — Ollama로 N개 동시 호출. 동시 8개면 82s → ~12s 기대.
   `CompletableFuture` + bounded executor 형태(코드 ~20줄, 외부 의존성 0).
3. (보류) extract 가속 — Tika 페이지 분할 병렬화. 한국어 owners 매뉴얼이
   유달리 무거움. 작업량 큼.
4. (보류) 비동기 응답 — devtools 사고와 broken pipe 모두 구조적으로 제거.
   작업량 중.

이 결과는 추정이 아니라 측정이다. 추정으로 Phase 2/3을 동시에 건들지 말 것.

---

## 9) Phase 2 적용 — embed 병렬화 + compact threshold 상향 (2026-06-24)

§8 측정 결과 `embed=53%`, `save=10%`를 **둘 다 같은 PR에서** 처리.

### 9-1. 임베딩 병렬 호출 (`IngestionService.ingestText`)

기존 단일 for 루프(임베딩→저장→인덱스 한 청크씩 직렬) → **두 단계로 분리**.

- **Phase A — 병렬 임베딩**
  ```
  pool = Executors.newFixedThreadPool(embedConcurrency)  // 기본 8
  for each expanded chunk:
      pool.submit(() -> embeddings[i] = embeddingService.embedDocument(text))
  allOf(futures).join()
  ```
  - 약어 확장은 사전에 일괄 수행 → 임베딩 스레드 간 글로서리 락 경합 없음.
  - 임베딩 결과는 원래 인덱스 위치에 보관(순서 보존).
  - pool은 ingest 1회당 생성·종료(매뉴얼 적재가 빈번하지 않으므로 단순함 우선).
- **Phase B — 직렬 save + index**
  - `TieredArticleStore.maxId` 카운터와 파티션 파일 쓰기가 상태 의존이라
    그대로 직렬 유지. embed가 압도적으로 컸기 때문에 여기 병렬화는 ROI 작음.

설정 (`application.yaml`):
```yaml
ingest:
  embed:
    concurrency: 8   # Ollama 부하 보고 4~16 조정. 1이면 직렬과 동등.
```

**기대 효과**: 82s 직렬 → 동시 8 기준 ~12s (Ollama 측 동시성에 의존).
1로 낮추면 회귀(직렬). 코드 변경 없이 yaml만으로 A/B 가능.

### 9-2. Compact threshold 100 → 500

매뉴얼 1권(수백 청크)을 적재할 때 hot이 100을 넘으면서 compact가 3\~4회
발동 → save 누적 15s. threshold를 500으로 올리면 한 권 안에서 compact가
거의 발동 안 함(366 청크라면 0회).

설정:
```yaml
storage:
  tier:
    threshold: 500
```

**기대 효과**: save 15s → ~3s.

### 9-3. 측정 계획 (다음 ingest)

같은 파일(IONIQ5 KR)을 새 ns(`test-bench-v2`)로 재적재해 새 `[ingest-timing]`을
얻는다. 비교 표는 본 문서에 채워 넣을 것.

| 단계 | §8 (직렬, threshold=100) | §9 측정(동시=8, threshold=500) | Δ |
|---|---:|---:|---:|
| total   | 154,509 ms | (다음 측정) | |
| extract | 54,367 ms  | ≈동일 (변경 없음) | 0 |
| embed   | 82,148 ms  | (목표 ~10–15s) | |
| save    | 15,830 ms  | (목표 ~3s) | |
| dedup/chunk/index | <500 ms 합 | ≈동일 | 0 |

### 9-4. 주의·되돌릴 신호

- Ollama가 동시 임베딩에 503/타임아웃을 뱉으면 `concurrency`를 4까지 낮추거나
  1(직렬)로 회귀. EmbeddingClient에는 현재 재시도가 없으므로 실패 1건 = 청크 1개 누락.
- save가 여전히 큰 비중이면 — JSONL append 디스크 sync 자체일 가능성. fsync 정책 점검.
- embed 시간 단축이 6배 미만이면 — Ollama가 단일 모델 인스턴스로 사실상
  직렬화 처리하는 것일 수 있음 (`OLLAMA_NUM_PARALLEL` 설정 확인).

