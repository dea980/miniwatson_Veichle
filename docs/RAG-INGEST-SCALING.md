# RAG 인제스트·저장 병목 분석과 목표 아키텍처

대용량 매뉴얼(현대 전 라인업 458권)을 적재하며 드러난 병목을 측정값 기준으로 분해하고,
"프로토타입 RAG → 운영 가능한 RAG 파이프라인"으로 가는 목표 구조와 단계를 정리한다.

---

## 0. 측정된 사실 (Numbers first)

| 항목 | 값 | 출처 |
|---|---|---|
| 매뉴얼 PDF | **458권** (KR 445 + EN 13), 권당 10~30MB·수백 쪽 | `data/vehicle/manuals/` |
| 권당 청크 | **~300~450** (예: NEXO 303, ST1 446) | KB 적재 결과 |
| 전체 청크(전량 적재 시) | **≈ 16만** | 458 × 350 |
| 임베딩 모델 | granite-embedding:278m, **768-dim**, Ollama | application.yaml |
| 임베딩 호출 | **청크당 1 HTTP** (배치 없음) | IngestionService 루프 |
| 적재 경로 | `POST /api/data/ingest-file` **동기**(요청 1건이 추출→청킹→임베딩 전부) | DataController |

**핵심 관찰**: 리포트 500은 토큰 문제가 아니었다(프롬프트 1,327자). 진짜 비용은
**적재 시 청크당 동기 임베딩**과 **저장 레이어의 전체 재작성**이다.

---

## 1. 병목 분해 (3 레이어)

### (A) 추출·청킹 — 경미
Tika가 PDF 전체를 한 번에 텍스트화 → recursive 청킹. 권당 1회라 비용은 작다.
개선: 청크 max-size 1000→**1400**(청크 수 ↓). 매뉴얼은 표/섹션 보존 청킹이 더 좋지만 우선순위 낮음.

### (B) 임베딩 — **주 병목**
- 청크마다 Ollama 임베딩을 **순차 1콜**. 권당 300~450콜 × 458권 = **수만~십수만 콜**.
- 한 권이 **HTTP 요청 1건에 동기로 묶임** → 권당 수 분, 요청 타임아웃·블로킹.
- 진행률·재시도·재개 없음. 중간 실패 시 처음부터.

### (C) 저장·검색 — **구조적 한계**
- **쓰기**: 콜드 스토어가 단일 `articles.parquet`였고, Parquet은 append 불가 →
  매 compact마다 **전체 삭제·재작성**(누적 O(N²)). → *문서별 파티션 parquet으로 1차 완화*.
- **검색**: 인메모리 brute-force/LSH. 16만 × 768-dim을 JVM 힙에 올려 전수 비교 →
  힙 폭증 + 검색 지연. Parquet/JSON은 **ANN·쿼리 엔진이 아니다**.
- **영속**: dev는 H2 in-memory(create-drop) → 재시작마다 증발·재적재.
- **dedup**: `loadAll()` 전체 스캔 O(N) → 누적 O(N²). → *카탈로그 존재검사 O(1)로 교체*.

---

## 2. 이미 적용한 1차 완화 (이번 라운드)
- 콜드 스토어 = **문서별 파티션 Parquet**(`./data/articles/`, 변경 파티션만 기록).
- dedup = **DocumentCatalog O(1)**.
- nextId = **카운터 캐시**(청크당 loadAll 제거).
- 청크 **1400**.
- 파일명 **일관 규칙**(`hyundai_<year>_<model>[_variant]_<projCode>_owners_<KR|EN>.pdf`) → 자동 분류.

→ 쓰기 O(N²)·dedup O(N²)는 해소. **임베딩 동기성·인메모리 검색은 그대로** → 구조 전환 필요.

---

## 3. 목표 아키텍처 — 오프라인 인제스트 / 온라인 서빙 분리

핵심 원칙: **무거운 적재는 요청 경로 밖(배치)**, 서빙은 stateless·빠르게.

```
[오프라인 인제스트 파이프라인 (배치/DAG)]
 manifest → download → extract → chunk → embed(배치·병렬) → upsert(pgvector)
   └ 각 단계 idempotent · 재시도 · 재개 · 관측. 카탈로그 = 진실원천.

[원본 아카이브]  per-doc Parquet (재현성·감사)        ← 인덱스 아님
[서빙 인덱스]    pgvector(HNSW, cosine) + Postgres 메타 ← ANN·영속

[온라인 서빙 (Spring, stateless)]
 질의 → 질의 임베딩 → pgvector ANN → rerank → LLM
```

- **저장/검색**: `VECTOR_STORE=pgvector`(이미 구현). `vector(768)` + HNSW(`vector_cosine_ops`).
  영속(볼륨) + ANN으로 16만 chunks도 견딤. Parquet은 원본/재현 레이어로 남김.
- **임베딩**: 요청에서 분리해 **배치·병렬 워커**로. (Ollama는 콜당이지만 동시성 풀로 처리량↑;
  또는 전용 임베딩 서비스/배치 API.)
- **오케스트레이션(DAG)**: 458권 × 다단계 × 실패가정 = 정당. 단 DAG는 *껍데기*라
  처리량은 임베딩 분리가 해결. 도구는 **Dagster/Prefect**(Airflow는 운영 과중 → 비추).
  Dagster면 **매뉴얼 1권 = asset**으로 멱등·캐싱·백필이 자연스럽다.

---

## 4. 마이그레이션 단계 (점진·저위험)

1. **pgvector 전환** ✅ 준비완료 — `docker compose up -d` + `VECTOR_STORE=pgvector` 재기동.
   인메모리 → 실제 벡터DB. 즉효, 코드 변경 0.
2. **임베딩을 요청에서 분리** — bulk 적재 워커 + 동시성 풀(진행률/재시도). 권당 타임아웃 제거.
3. **Dagster asset 파이프라인** — `fetch→download→extract→chunk→embed→upsert`,
   권당 asset·재개·관측·백필. ← JD(데이터 파이프라인)용 핵심 산출물.
4. **부팅 최적화** — `rebuild()`가 기동마다 전건 재insert → "pgvector 행수==카탈로그면 생략" 가드.

---

## 5. 결정 로그 / 트레이드오프
- **Parquet 유지 이유**: 원본 청크의 불변 아카이브·재현성. 단 인덱스로는 부적합 → pgvector 분리.
- **Airflow 대신 Dagster/Prefect**: 단일 노드·소규모에 풀스케줄러는 과중. asset 모델이 멱등성에 적합.
- **선별 적재 원칙**: 전량(16만) X. 케이스 차종(ELANTRA·SONATA·TUCSON·SANTA FE·PALISADE·KONA)
  + 신형/EV(IONIQ·NEXO)부터. 품질·노이즈·비용 모두 유리.
- **청크 1400 + 소스당 600자 표출**: 임베딩 수↓하되 검색 품질 영향 미미.

---

## 6. 증상 `exit 255` (JVM 크래시) — 진단과 처방

**증상**: `spring-boot:run` 이 `Process terminated with exit code: 255`. Spring 예외 스택 없이 종료 +
리포 루트에 `javacore.*`/`hs_err`/`Snap.*` 덤프 → **JVM 네이티브 크래시(주로 OOM)**, 애플리케이션 예외 아님.

**원인(병목의 직접 발현)**:
- 임베딩을 **박싱된 `List<Float>`** 로 보관 — float당 객체 오버헤드 ~16B(원시 `float[]`의 4배).
- 아티클(텍스트+임베딩)을 **전건 인메모리 캐시**(Parquet 캐시/Tiered)로 보유.
- `PgVectorStore.rebuild()` 가 기동마다 `store.loadAll()` 로 **전건을 힙에 적재** 후 insert.
- 16만 chunks × 768-dim ≈ **2GB+** (박싱·리스트 오버헤드 포함) → 기본 힙 초과 → 크래시.
- 즉, **많이 적재할수록 부팅이 죽는** 구조. exit 255는 "데이터가 메모리에 다 올라간다"의 신호.

**즉시 처방(운영 회피)**:
1. 힙 상향: `./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx4g"`
   (jar: `java -Xmx4g -jar target/*.jar`). 단 박싱 때문에 근본 해결 아님 — 시간 벌기.
2. **선별 적재 유지** — 수천 chunk 수준(케이스 차종 + 신형/EV)만. 전량 16만 X.
3. **기동 rebuild 생략 가드** — pgvector 행수 == 카탈로그면 rebuild 스킵(부팅 시 전건 재적재 회피).
4. 크래시 원인 확정: `javacore`/`hs_err` 첫 페이지의 `Failure reason`(OutOfMemory 여부) 확인,
   또는 `./mvnw ... -e` 로 forked JVM 종료 코드 맥락 확인.

**근본 해결(우선순위)**:
- **임베딩을 원시 `float[]`로** 저장·전송(박싱 제거 → 메모리 ~1/4, GC 압력↓). `Article.embedding` 타입 변경 + Parquet/pgvector 직렬화 동반.
- **loadAll 전건 적재 제거** — pgvector를 서빙 단일 인덱스로 삼고, 부팅 시 전건 인메모리 보유 안 함(스트리밍/페이지네이션 적재, rebuild는 증분).
- **임베딩을 요청·부팅 경로 밖**(배치 워커)으로 — §3·§4.

→ exit 255는 §3 목표 아키텍처(인메모리 → pgvector 단일 인덱스 + 임베딩 배치 분리)로 가야 사라진다.
   힙 상향·선별 적재는 그 전까지의 임시 방편.

---

## 7. 모니터링 지표 (운영 전환 시)
- 적재: 권당 청크 수, 임베딩 처리량(chunks/s), 단계별 실패율·재시도, 재개 지점.
- 검색: pgvector ANN p50/p95 지연, recall(골든셋), ef_search 대비 정확도.
- 저장: pgvector 행수 vs 카탈로그(정합성), 벡터 테이블 크기, 부팅 rebuild 시간.

---

## 8. 병렬 · 메모리 · DAG의 관계 (자주 하는 오해 — 인터뷰 정답)

**관찰된 사고**: 선별 10권을 `--workers 2`로 적재 → 2권 성공 후 백엔드 `exit 255`(OOM).
이어 나머지 권은 `Connection refused`(서버 완전 다운). **직렬이었다면 더 늦게 죽었을 것** —
즉 병렬이 메모리 한계를 먼저 때렸다. "병렬 = 항상 빠름"이 깨지는 지점.

### 8.1 "메모리에 맞춰 병렬하면 되지 않나? 그래서 DAG가 필요한 것 아닌가?"
원칙(메모리 예산 안에서 동시성 제한 = bounded concurrency)은 **옳다**. 하지만 그걸 강제하는
주체가 DAG라는 건 **틀리다**. 메모리가 터지는 위치를 보면 명확하다:

```
ingest 스크립트 → POST /ingest-file → [Spring 백엔드: PDF파싱 + 청크 임베딩 + 전체 articles 힙 보유 + saveAll]
                                        ↑ 힙이 터지는 곳 = 이 JVM 프로세스 내부
```

DAG(Airflow/Dagster)는 **그 엔드포인트를 바깥에서 호출**할 뿐 백엔드 JVM 힙을 보지도 제어하지도
못한다. 그래서 DAG가 4-way로 같은 엔드포인트를 때리면 백엔드는 **더 빨리** 죽는다(이번 workers=2와
동일 메커니즘, 배수만 큼). **DAG는 스케줄링·재시도·재개·관측을 주지, 메모리 안전을 주지 않는다.**

### 8.2 올바른 순서 (뒤집으면 "크래시를 오케스트레이션"하게 됨)
1. **단위당 메모리 bound** — `Article.embedding`을 박싱 `List<Float>`→원시 `float[]`(메모리 ~1/4),
   부팅·적재 시 전건 인메모리 보유 제거(스트리밍/페이지네이션). **한 권의 풋프린트가 예측 가능·작아야**
   비로소 병렬이 가능. *지금은 1권도 수 GB를 먹어 병렬의 전제 자체가 없음.*
2. **메모리에 맞춘 병렬 = 워커풀 세마포어** — 동시성을 `min(코어, Ollama 처리량, 힙/단위풋프린트)`로
   제한. "메모리에 맞춘 병렬"은 *여기서* 산다. `ingest_existing_manuals.py`의 `auto_workers()`
   (배치 규모로 1~4 자동)가 그 1차 버전 — 단 (1)이 선행돼야 안 죽는다.
3. **DAG는 그 위 운영 레이어** — 458권 백필의 멱등·재시도·재개·관측·백필. (1)(2) 이후 *마지막에* 얹음.
   도구는 Dagster(asset-per-book) 권장(§3). Airflow/Spark는 이 규모에 과중·역효과.

### 8.3 처리량의 천장 (병렬로도 못 넘는 벽)
로컬 Ollama는 **단일 인스턴스**라 임베딩은 GPU-bound. 병렬 HTTP는 거기서 큐잉돼 **선형 가속이 안 됨**.
병렬의 실이득은 "A권 PDF 파싱(CPU) ↔ B권 임베딩(GPU)" 구간 겹침뿐 → **sublinear·상한 존재**.
진짜 N배는 (a) batch 임베딩 API, (b) 임베딩 인스턴스 복수화 — **DAG로는 안 풀린다**.

### 8.4 한 줄 결론
> 병렬은 빠르고 메모리에 맞춰야 한다(맞음). 그 "맞춤"은 **워커풀 세마포어**가 하고,
> DAG는 그 위에서 재시도/재개를 관리할 뿐 메모리 문제 자체의 해법이 아니다.
> `float[]`(§8.2-1) 없이 DAG부터 가면 — 이번처럼 된다.

### 8.5 병렬의 '올바른 레이어' (코드 재확인으로 정정)
크래시 후 코드를 다시 보니 **백엔드가 이미 요청 1건 안에서 임베딩을 N-way 병렬** 처리하고 있었다:
`IngestionService` → `Executors.newFixedThreadPool(ingest.embed.concurrency)`(기본 8). 즉 **권당
8스레드 임베딩이 이미 돈다.** 여기서 적재 스크립트가 권을 또 `--workers 2`로 병렬화 →
**2권 × 8스레드 = 동시 임베딩 16 + 대형 PDF 2개 동시 파싱** → 힙 2배 → `exit 255`.

⇒ **정정**: 병렬의 올바른 레이어는 '권 fan-out'(스크립트)이 아니라 **백엔드의 embed 풀**이다.
  - **권은 직렬**(1권=PDF 파싱 1개, Tika/PDFBox 대형 문서 파싱이 진짜 transient 피크)로 두어
    메모리를 bound한다. `ingest_existing_manuals.py` 기본을 `--workers 1`로 되돌림.
  - **처리량은 `ingest.embed.concurrency`** 로 조절 — 단일 PDF 파싱 footprint 위에서만 스레드가
    늘어 메모리 증가가 선형·예측 가능. 이게 "메모리에 맞춘 병렬"의 실제 구현 지점(§8.2-2).
  - 검증된 사실: pgvector 모드에선 `VectorIndex`(@ConditionalOnProperty `vector.store=memory`)가
    아예 로드되지 않음 → 이번 OOM의 지배적 할당자는 박싱 임베딩보다 **동시 PDF 파싱**이었다.
    `float[]`(§8.2-1)은 16만-chunk 정상상태 힙엔 유효하나 *이번 크래시의 직접 원인은 아님*.

**교훈**: "병렬을 어디에 둘 것인가"가 "병렬을 할 것인가"보다 중요하다. 같은 동시성도
요청 경로 안(메모리 공유 프로세스)에 N배로 쌓으면 처리량이 아니라 크래시를 N배 한다.

### 8.6 단일 노드 RAM 천장 (exit 137 ≠ exit 255)
- `exit 255` = **JVM 내부 OOM**(힙 초과, 애플리케이션 레벨). 처방: 힙↑·동시성↓·선별 적재.
- `exit 137`(=128+9, SIGKILL) = **OS가 밖에서 죽임** = 머신 전체 물리 RAM 고갈. `-Xmx`를 올린 게
  오히려 방아쇠 — JVM은 `-Xmx`(힙) **외에 off-heap**(Parquet/Tika/Hadoop 네이티브, direct buffer,
  metaspace)을 2~4GB 더 쓴다. 측정 사례: 24GB 맥북에어, Docker Desktop VM이 **7.7GB 선점** →
  가용 ~16GB. `-Xmx6g + off-heap ~3g + Docker 7.7g + macOS` → 한계 초과 → SIGKILL.
- ⇒ 단일 노드의 천장은 **JVM힙이 아니라 JVM+Ollama+Docker가 공유하는 총 물리 RAM**.
  힙은 그 안에 맞춰야 하며(이 머신은 `-Xmx4g`가 안전선), 그 이상 처리량은 노드를 못 넘는다.
  적재 중엔 임베딩 모델만 필요 → 큰 LLM(qwen3:8b)은 `ollama stop`으로 내려 RAM 확보.

### 8.7 동시성 튜닝 — 실측·방법론 (parity는 무관)
**실측 데이터(24GB 맥북, `-Xmx4g`, 직렬 권):**

| `ingest.embed.concurrency` | 결과 |
|---|---|
| 4 | 10/10 성공 ✅ |
| 8 | 0/8, 1권째 즉사 ❌ (Remote end closed → Connection refused) |
| 6 | 미측정(다음 후보) |

- **값은 짝수일 필요 없다.** `embed.concurrency`는 스레드풀 크기라 1·2·3·5·7 다 유효.
  4/6/8은 탐색 step 편의일 뿐 제약 아님.
- **튜닝 = 이분 탐색 + plateau 측정.** known-good(4)와 known-bad(8) 사이를 bisect: 6 먼저
  (6 실패면 7·8 단조성으로 자동 탈락, 6 성공이면 7 시도). 메모리 압력은 동시성에 **단조 증가**라
  7부터 보는 건 비효율(6이 7을 지배하는 probe).
- **'안 죽는 최댓값' ≠ '빨라지는 최댓값'.** 크래시 직전까지 쥐어짜지 말고 `time`으로 plateau를
  찾는다. 안전 운영값은 보통 크래시 천장보다 한 칸 아래.

**왜 4 근처에서 막히나 = 단일 Ollama 인스턴스(핵심).**
백엔드 스레드를 몇 개로 늘리든 전부 **Ollama 1개 프로세스 · 임베딩 모델 1개**로 깔때기처럼 모인다.
동시 처리 슬롯 수는 Ollama의 `OLLAMA_NUM_PARALLEL`이 정하고, **슬롯 1개당 컨텍스트/KV캐시 = 추가 RAM**.
8로 올렸을 때 Ollama가 슬롯을 더 띄우려다 RAM이 튀어 죽었다(parity가 아니라 *슬롯당 메모리 × 동시 슬롯*).
백엔드 스레드 > Ollama 슬롯이면 초과분은 큐 대기 → 처리량 이득 0, 메모리만 낭비.

⇒ **올바른 튜닝 = 백엔드 `embed.concurrency`를 Ollama `OLLAMA_NUM_PARALLEL`에 정렬.**
  진짜 N배 처리량은 (a) `OLLAMA_NUM_PARALLEL`↑(RAM 허용 내) (b) 임베딩 인스턴스 복수화
  (c) batch 임베딩 API — 스레드 parity로는 안 풀린다.

---

## 9. 왜 Parquet인가 — 그리고 정련안(임베딩은 pgvector 단일 출처)

### 9.1 왜 Parquet (포맷 선택의 근거)
- **vs JSON(hot 티어):** JSON은 행기반·무압축·무스키마. 16만 청크 × 768-dim을 JSON으로 두면
  용량 폭증·파싱 지연. Parquet은 **열기반 + Snappy 압축 + 스키마(Avro)** → 같은 데이터가 훨씬
  작고 타입 안전. 그래서 **hot=JSON(빠른 append·최근 소량) → cold=Parquet(압축·영속)** LSM 티어링.
- **vs DB에만 저장:** 불변 열-아카이브를 따로 두면 **458권 PDF 재파싱 없이** 벡터 인덱스를
  재구축·마이그레이션 가능. Tika PDF 추출이 최고 비용 단계라 한 번만 하고 Parquet에 고정한다.
- **상호운용:** DuckDB·pandas·Spark가 Parquet 네이티브 → 재처리/분석 파이프라인에 바로 물림.

### 9.2 역할 분리 (재확인)
Parquet = **원본 아카이브/리플레이/감사 진실원천**. pgvector = **ANN 서빙 인덱스**.
Parquet은 brute-force 스캔뿐이라 **인덱스가 아니다**. 검색은 전부 pgvector.

### 9.3 파티션은 '문서별'이 정답 (차종별 아님)
- 현 구조: `data/articles/<namespace>__<문서제목>__<hash>.parquet` = **매뉴얼 1권 = 1파일**(실측 확인).
- 차종별로 묶으면? (a) OOM 무관 — `loadAll()`이 어떻게 쪼개든 전건을 힙에 캐시(레이아웃≠힙점유).
  (b) 쓰기 증폭↑ — 같은 차종 새 연식 추가 시 그 차종 파티션 전체 재작성. 문서별은 1파일만 추가.
  (c) Parquet은 서빙 인덱스가 아니라 파티션 방식이 성능에 거의 무영향. ⇒ **문서별 유지**.
  (탐색 편의용 차종 디렉터리 중첩은 선택적 cosmetic — 서빙·메모리엔 무영향.)

### 9.4 정련안 — 임베딩은 Parquet에서 빼고 pgvector에만
**문제**: 임베딩이 Parquet·pgvector **양쪽에 중복** 저장. 그 768-dim 박싱 벡터가 Parquet 캐시를
힙에 무겁게 만드는 §6 OOM의 한 축.

**제안**: **Parquet은 텍스트+메타만, 임베딩은 pgvector 단일 출처.**
- 텍스트는 PDF 파싱이라 비쌈 → 아카이브 보관(재현용). 임베딩은 텍스트에서 Ollama로 **싸게 재생성** →
  아카이브에 둘 필요 없음.
- 효과: ① Parquet 경량화 → 힙 캐시 부담↓(OOM 완화) ② 임베딩 단일 출처(pgvector) ③ 임베딩 모델
  교체 시 **PDF 재파싱 없이** 텍스트만 다시 임베딩.
- 비용/위험: `float[]` 전면 리팩터(§8.2-1, ~10파일)보다 **위험 낮음** — Avro 스키마에서 `embedding`
  필드 제거 + Parquet write/read에서 임베딩 제외 + 부팅 rehydrate 경로 조정 수준. 단 기존 Parquet과
  스키마 호환성(마이그레이션) 1회 필요.
- 트레이드오프: pgvector 유실 시 임베딩 복구 = 텍스트에서 재임베딩(수 분~시간). 아카이브가 진실원천인
  것은 **텍스트**, 임베딩은 파생물이라는 관점 — 합리적.

→ 우선순위: 이 정련안(9.4)이 `float[]`(§8.2-1)보다 OOM 대비 가성비 높음. 다음 라운드 후보.

---

## 10. 임베딩 모델 다중화는 왜 안 쓰나 (데이터 타입별 모델 vs 단일 다국어 + 메타 필터)

질문: "데이터 타입에 맞춰 임베딩 모델을 바꾸면? 여러 임베딩 모델은?"

### 10.1 여러 모델이 까다로운 본질
1. **모델마다 벡터 공간이 다르다.** 모델 A의 768-dim과 모델 B의 768-dim은 차원이 같아도
   **코사인 비교가 무의미**(서로 다른 공간). 한 인덱스에 섞으면 랭킹이 깨진다 → "여러 모델 벡터를
   한 인덱스에서 함께 검색"은 불가.
2. **차원 고정 인덱스.** pgvector `vector(768)` 컬럼은 384/1024 모델을 못 받음 → 모델/차원마다
   별도 테이블·컬럼. 모델 수만큼 인덱스 증식.
3. **질의 라우팅 문제(진짜 난점).** 적재 시엔 타입을 안다(KR 매뉴얼→KR모델). 그러나 **질의 시**
   사용자 질문을 어느 모델 공간에서 찾나? 코퍼스가 모델별로 쪼개지면 "정답이 어느 인덱스인가"를
   알거나 전 인덱스 검색 후 머지해야 하는데, **공간이 달라 점수 머지가 사과-오렌지**. 그래서 대부분
   RAG가 코퍼스 전체에 **단일 임베딩 모델**을 쓴다.

### 10.2 여러 모델이 맞는 경우
모달리티/버티컬이 **disjoint**할 때만: 텍스트 vs 코드 vs 이미지(CLIP). 앱이 **질의 타입으로
어느 인덱스를 칠지 결정론적으로 라우팅**할 수 있을 때. "섞는" 게 아니라 "분리된 버티컬".

### 10.3 이 시스템의 결정 — 단일 다국어 모델
코퍼스가 전부 자동차 텍스트(한+영), 단일 모달리티. granite-embedding-278m은 이미
**EMBEDDINGS.md에서 한+영 recall 벤치 승자**. 모델을 더하면 라우팅·머지 복잡도↑ + 랭킹 깨짐 +
recall 이득 0. **단일 다국어 모델이 정답**(언어가 여러 개여도 언어별 모델보다 다국어 1개가 나음 —
공간 공유로 교차 검색·비교 가능).

### 10.4 "데이터 타입별 조절"의 실용 버전 = 메타 필터 (모델 분기 아님)
스키마에 이미 `carCode/carModel/year/powertrain/lang/region` 보유(§ArticleParquetStore).
**단일 임베딩 공간 위에서 pre-filter**로 type-aware 검색을 준다 — 차원·라우팅·머지 문제 전부 회피:
```sql
-- "EV 매뉴얼만" — 모델 하나로 type-aware
WHERE powertrain='electric' ORDER BY embedding <=> ?::vector
```

### 10.5 메모리 경고
모델 다중화 = Ollama가 **여러 모델을 RAM에 동시 상주** = §8.7에서 8로 터진 메모리 압력의 배수.
24GB 단일 노드에선 특히 나쁜 선택. ⇒ 단일 모델 + 메타 필터가 **더 강하고·단순하고·메모리 안전**.
