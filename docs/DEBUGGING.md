# MiniWatson — Debugging Guide

> 부팅 안 됨 / 응답 이상함 / 모델 답 헤맴 / Parquet 깨짐 등 발생 시 이 문서를 첫 번째로 본다.

---

## 0. 30초 진단 트리

```
앱이 안 뜸 ─────────► 1 Boot Failures
앱은 떴는데 /api/rag/models 가 응답 안 함 ─► 2 Network / Port
ingest 가 실패 ─────────────────────► 3 Ingestion Failures
RAG 답이 이상하거나 느림 ────────────► 4 LLM / Embedding
Parquet 파일 관련 에러 ───────────────► 6 Parquet
governance log가 안 쌓임 ─────────────► 7 H2 / JPA
원인 모름 ────────────────────────────► 8 Observation Toolkit
```

---

## 1. Boot Failures

### 1.1 `IllegalAccessError`, `Subject.getSubject()` 관련 stacktrace

**원인**: Java 17+ 가 `Subject.getSubject(AccessControlContext)` 를 deprecated → Hadoop `UserGroupInformation` 가 호출 시 fail.

**해결**: `pom.xml` 의 `spring-boot-maven-plugin` 에 다음이 이미 박혀 있는지 확인:
```xml
<configuration>
    <jvmArguments>-Djava.security.manager=allow</jvmArguments>
</configuration>
```

직접 `java -jar` 로 실행할 땐:
```bash
java -Djava.security.manager=allow -jar target/miniwatson-0.0.1-SNAPSHOT.jar
```

### 1.2 `Failed to load article.avsc`
**원인**: `src/main/resources/article.avsc` 가 누락되었거나 build classpath에서 빠짐.

**해결**:
```bash
ls src/main/resources/article.avsc                  # 존재 확인
./mvnw clean compile                                # 재빌드
jar tf target/*.jar | grep article.avsc             # JAR 안에 들어갔는지
```

### 1.3 `Port 8080 already in use`
```bash
lsof -i :8080
kill -9 <PID>
# 또는
SERVER_PORT=8081 ./mvnw spring-boot:run
```

### 1.4 Lombok-related compile errors (`cannot find symbol getXxx`)
**원인**: IDE의 annotation processor 미활성 또는 `pom.xml`의 `annotationProcessorPaths` 빠짐.

**해결**:
- IntelliJ: `Preferences → Build → Compiler → Annotation Processors → Enable`.
- Lombok plugin 설치.
- `./mvnw clean compile` 로 CLI에서는 잘 도는지 우선 확인.

### 1.5 H2 `Database "mem:miniwatson" not found`
거의 발생 안 함 (in-memory는 자동 생성). 발생 시 `application-dev.yaml` 의 들여쓰기 확인 (`hibernate:` / `ddl-auto:` 줄).

---

## 2. Network / Port

### 2.1 `/api/rag/models` curl이 멈춤
- 앱이 진짜 떴는지 로그 확인 (`Started MiniwatsonApplication in X seconds`).
- 다른 인터페이스에서 listen 중일 수도: `lsof -nP -iTCP:8080 -sTCP:LISTEN`.

### 2.2 브라우저 dashboard가 안 뜸
- `http://localhost:8080/` 가 `index.html` 을 서빙해야 정상 (Spring Boot static resource).
- 콘솔 (DevTools) 에서 CORS / 404 / mixed content 확인.

---

## 3. Ingestion Failures

### 3.1 `403 Forbidden` from Wikipedia
**원인**: User-Agent 미설정.

**확인**: `IngestionService.ingest()` 안에 다음이 살아 있어야 함:
```java
headers.set("User-Agent",
   "MiniWatson/1.0 (https://github.com/dea980/miniwatson; kdea989@gmail.com)");
```

Wikipedia REST API 정책: <https://api.wikimedia.org/wiki/Wikimedia_REST_API#Terms_and_conditions>

### 3.2 `404 Not Found` from Wikipedia
- title 표기 확인. 공백은 `_`, 대소문자 정확히 (`Retrieval-augmented_generation`, not `retrieval-augmented_generation`).
- redirect 페이지는 summary endpoint가 따라가지 않을 수 있음.

### 3.3 ingest는 성공했는데 `/api/data/articles` 가 빈 배열
**원인**: 작업 디렉토리 차이.
- `./mvnw spring-boot:run` 은 보통 프로젝트 루트에서 실행 → `./data/articles.parquet` 가 거기 생성.
- IDE Run Configuration이 다른 cwd면 다른 위치에 파일 생성. **`pwd`** 확인.

```bash
find . -name "articles.parquet" -type f
```

### 3.4 `NullPointerException` 안에 `content_urls`
**원인**: `WikipediaResponse.ContentUrls` / `Desktop` 의 `static` 누락 → 역직렬화 실패 → null.

**확인**:
```java
public static class ContentUrls { ... }   // static
public static class Desktop     { ... }   // static
```

### 3.5 batch ingest가 중간에 멈춤
- 각 article은 try-catch로 격리되어 있어 한 건 실패해도 다음 진행 (`DataController.ingestBatch`).
- 그래도 멈춘다면 Ollama embedding 호출이 hang일 가능성 — 4.4 참조.

---

## 4. LLM / Embedding

### 4.1 응답이 매우 짧음 ("...") 또는 비어 있음
**원인 1**: `num_predict` 부족.
```bash
OLLAMA_NUM_PREDICT=1500 ./mvnw spring-boot:run
```

**원인 2**: gemma3/4의 thinking 토큰이 토큰 예산 잡아먹음.
- `OllamaRequest.think = false` 가 살아 있는지 확인 (`OllamaService.ask()`).
- `think:true` 로 두면 latency 2~3× + 답변이 잘림.

### 4.2 모델이 컨텍스트 무시
- prompt 형식이 모델과 안 맞을 수 있음. `RagService` 의 prompt template 점검:
  ```
  Based on this context:
  - {title}: {summary}
  ...
  Answer the question concisely: {question}
  ```
- qwen / llama로 교체 시 `<|system|>...<|user|>...` 등의 chat template 필요할 수 있음.

### 4.3 `IndexOutOfBoundsException` in cosine
**원인**: 임베딩 차원 mismatch. 모델 교체 시 (e.g. `mxbai-embed-large` = 1024 dims) 기존 articles는 768 dims → 매칭 안 됨.

**해결**:
- 기존 Parquet 삭제: `rm ./data/articles.parquet`
- 새 embedding 모델로 ingest 다시.

### 4.4 Ollama hang / timeout
- `OllamaService`/`EmbeddingService` 의 `RestTemplate` 에 timeout 미설정. 무한 대기 가능.
- 임시 대응: Ollama 재시작 (`ollama stop && ollama serve`).
- 영구 대응 ([SDS.md TD-3](./SDS.md#7-known-tech-debt-구체적-위치--권장-조치)):
  ```java
  RestTemplate rt = new RestTemplateBuilder()
      .setConnectTimeout(Duration.ofSeconds(5))
      .setReadTimeout(Duration.ofSeconds(60))
      .build();
  ```

### 4.5 `nomic-embed-text` 응답이 빈 배열
- Ollama가 모델을 첫 호출에서 download/load 하느라 첫 요청 latency 큼. 두 번째부터 정상.
- `ollama pull nomic-embed-text` 수동 실행으로 미리 받아두기.

---

## 5. Landmines — Don't Touch Without Reading

다음 코드들은 **이유가 있어서** 그렇게 되어 있습니다. 함부로 "리팩토링" 하지 마세요.

### 5.1 `OllamaRequest.think = false`
- gemma3/4 reasoning tokens가 num_predict 예산을 먹어 응답 잘림 + latency 3×.
- 다른 모델은 `think` 옵션을 무시하므로 항상 false로 두는 게 안전.

### 5.2 `IngestionService` User-Agent 헤더
- 빼면 Wikipedia 403. 운영 시 User-Agent 의 contact info 갱신 필요.

### 5.3 `Article.embedding` `@JsonProperty(WRITE_ONLY)`
- 빼면 모든 응답에 768-dim float 배열 노출 → 응답 50KB+. 클라이언트 부담 + storage detail 누출.

### 5.4 `WikipediaResponse.ContentUrls/Desktop static`
- non-static inner class는 Jackson이 outer instance 없이 새로 못 만듦 → 역직렬화 실패.

### 5.5 `ArticleParquetStore.saveAll()` 의 "delete then write"
- Parquet은 in-place mutation 불가. 의도된 동작.
- 단, 동시 쓰기 race는 [SDS TD-4](./SDS.md) 참조.

### 5.6 `AvroParquetReader` 에 `withDataModel()` **호출 금지**
- Parquet 1.14에서 reader의 withDataModel은 deprecated/NPE. writer만 사용.

### 5.7 `pom.xml` `-Djava.security.manager=allow`
- Java 21 + Hadoop 호환. 빼면 부팅 실패 (1.1).

### 5.8 `application.yaml` 의 `OLLAMA_*` env override
- 코드에 모델명/URL 박혀 있지 않음. 변경 시 `@Value` 키 일치 확인.

---

## 6. Parquet 트러블슈팅

### 6.1 `articles.parquet` 가 손상된 것 같다
```bash
# Avro/Parquet CLI로 확인
brew install parquet-tools
parquet-tools cat ./data/articles.parquet | head -1
parquet-tools schema ./data/articles.parquet
```

복구 불가하면 깔끔히 삭제 후 ingest 재실행:
```bash
rm ./data/articles.parquet ./data/.articles.parquet.crc
```

### 6.2 첫 ingest 후 두 번째 ingest에서 `Failed to delete existing Parquet file`
- 파일 핸들 leak 또는 권한 문제. 프로세스 모두 종료 후 권한 확인:
```bash
lsof | grep articles.parquet
chmod 644 ./data/articles.parquet
```

### 6.3 `loadAll()` 에서 `ClassCastException` on embedding
- Avro `array<float>` 가 reader 측에서 `List<?>` 로 들어옴. `ArticleParquetStore.loadAll()` 의 안전한 cast 패턴이 이미 적용됨:
  ```java
  if (embObj instanceof List<?> rawList) {
      for (Object item : rawList) {
          if (item instanceof Number num) embedding.add(num.floatValue());
      }
  }
  ```
  수정 시 이 패턴 유지.

### 6.4 `Could not load embedding column`
- Schema 불일치. `article.avsc` 와 실제 .parquet 의 schema 확인:
  ```bash
  parquet-tools schema ./data/articles.parquet
  ```
- Schema가 옛날 버전이면 [ERD 5.3 Migration recipe](./ERD.md#53-migration-recipe--article에-language-필드-추가) 참고.

---

## 7. H2 / JPA

### 7.1 governance log가 응답에 빈 배열
- `dev` 프로파일은 in-memory → 재시작하면 다 날아감.
- 영구 보관하려면 `--spring.profiles.active=demo`.

### 7.2 H2 콘솔 접속 안 됨
- `dev`/`demo` 프로파일에서만 `h2:console:enabled: true`.
- prod 프로파일에서는 활성화 안 됨 (보안).
- `application-dev.yaml` 의 들여쓰기 한 칸 어긋남 확인.

### 7.3 `Could not commit JPA transaction`
- H2 file 모드에서 다른 프로세스가 lock 잡고 있을 때. `AUTO_SERVER=TRUE` 가 설정되어 있으나, 그래도 충돌 시:
```bash
rm ./data/miniwatson.mv.db ./data/miniwatson.trace.db
```

---

## 8. Observation Toolkit

### 8.1 로그 레벨 조정
임시 (env 또는 args):
```bash
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments="--logging.level.com.miniwatson=DEBUG \
                               --logging.level.org.springframework.web=DEBUG"
```

### 8.2 RAG 흐름 추적
`RagService` 에 이미 다음 로그가 있음:
```
RAG question: {}
Retrieved {} articles: {}
Augmented prompt length: {} chars
Ollama answer length: {} chars
```
`logging.level.com.miniwatson.service.RagService=DEBUG` 또는 `INFO` 활성.

### 8.3 SQL 로깅
`dev`/`demo` 프로파일에 `show-sql: true` 가 있어 query_log INSERT가 콘솔에 찍힘.

### 8.4 Ollama 자체 디버그
별도 터미널:
```bash
ollama logs        # macOS: ~/.ollama/logs/server.log
# 또는
OLLAMA_DEBUG=1 ollama serve
```

### 8.5 네트워크 패킷 확인
```bash
# Ollama 호출 trace
curl -v http://localhost:11434/api/tags

# Wikipedia 호출 reproduce
curl -v -H "User-Agent: MiniWatson/1.0 (test)" \
  "https://en.wikipedia.org/api/rest_v1/page/summary/RAG"
```

### 8.6 Parquet 빠른 inspect
```bash
parquet-tools rowcount ./data/articles.parquet
parquet-tools head -n 1 ./data/articles.parquet
parquet-tools meta ./data/articles.parquet  # SNAPPY 압축 통계
```

---

## 9. "이런 증상은 99% 이거다" cheat sheet

| 증상 | 가장 흔한 원인 |
|---|---|
| `/api/rag/models` 자체가 응답 없음 | 앱이 떠 있지 않거나 다른 포트 |
| ingest 403 | User-Agent 미설정 (코드 회귀) |
| ingest 404 | title 오타 / 대소문자 |
| RAG 답이 잘리거나 짧음 | `num_predict` 부족 또는 `think:true` 회귀 |
| RAG 응답 매우 느림 | Ollama 첫 호출 cold load |
| `No articles in knowledge base` | Parquet 파일 없음 (cwd 다름 or 삭제됨) |
| governance logs 텅 비어 있음 | dev 프로파일에서 재시작 후 in-mem 초기화 |
| `IndexOutOfBoundsException` (cosine) | embedding 모델 교체 후 기존 데이터와 차원 mismatch |
| Parquet write 실패 | 직전 실행이 파일 핸들 안 닫음 (강제 종료) |
| Lombok getter 없다고 컴파일 fail | annotation processor 미설치 |

---

## 10. 마지막 수단 — Clean Reset

데이터 모두 날리고 클린 상태로:
```bash
./mvnw clean
rm -rf data/articles.parquet data/.articles.parquet.crc \
       data/miniwatson.mv.db data/miniwatson.trace.db \
       target/
ollama stop
ollama serve &
./mvnw spring-boot:run
```

위로 안 풀리면:
1. `git status` — 본인이 모르게 건드린 파일 확인.
2. `git stash` 후 last known-good commit 으로 checkout.
3. README `Quick Start` 의 prerequisites 처음부터 다시.
