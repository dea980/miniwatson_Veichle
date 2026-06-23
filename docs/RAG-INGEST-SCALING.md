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
