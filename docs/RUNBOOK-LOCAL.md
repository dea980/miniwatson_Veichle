# 로컬 실행 런북 (정석 포트 맵 · 기동 순서)

포트 충돌(특히 Airflow ↔ 백엔드 8080)으로 헤매지 않도록 **고정 포트 맵과 기동 순서**를 못박는다.

## 포트 맵 (고정)

| 서비스 | 포트 | 비고 |
|---|---|---|
| **vehicle 백엔드 (Spring)** | **8080** | 기준. frontend 프록시·ingest 스크립트·Dockerfile EXPOSE 전부 8080. **옮기지 말 것.** |
| vehicle 프론트 (Next dev) | 3000 | `next.config`가 `/api/* → localhost:8080` 프록시 (BACKEND_URL로 override 가능) |
| vehicle pgvector | 55433 | `docker-compose.yml`, 컨테이너 `miniwatson-veichle-pg` |
| Ollama (임베딩·LLM) | 11434 | **호스트**(Mac Metal). 컨테이너화 금지(느림) |
| **Airflow webserver** | **8088** | ⚠️ 기본 8080에서 **비켜야 함**(아래) — 백엔드와 충돌 방지 |
| (원본) miniwatson pg | 55432 | 다른 프로젝트. vehicle과 무관 |

## Airflow를 8080 → 8088 로 (충돌 해소, Airflow 프로젝트에서)
- docker compose면 Airflow compose의 webserver 포트 `"8080:8080"` → `"8088:8080"` 후 `docker compose up -d`.
- standalone이면:
  ```bash
  pkill -f "airflow webserver"
  AIRFLOW__WEBSERVER__WEB_SERVER_PORT=8088 airflow webserver -p 8088 -D
  ```

## 기동 순서 (정석)
```bash
cd ~/Desktop/miniwatson_Veichle

# 1) pgvector (이미 떠 있으면 생략) — healthy 확인
docker compose up -d
docker ps --format '{{.Names}} {{.Status}} {{.Ports}}' | grep veichle   # 55433, (healthy)

# 2) 8080 비었는지 (Airflow 8088로 옮겼으면 비어 있음)
lsof -i :8080 || echo "free"

# 3) 백엔드 (pgvector 모드, 기본 8080) — 이 터미널 유지
VECTOR_STORE=pgvector ./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx4g"
#   로그: Tomcat started on port 8080 + Started MiniwatsonApplication

# 4) (다른 터미널) 프론트
cd frontend && npm run dev      # http://localhost:3000

# 5) (다른 터미널) 선별 적재 — 기본 8080이라 MW_INGEST_URL 불필요
python3 scripts/ingest_existing_manuals.py --models ioniq5,ioniq6,nexo,palisade --years 2025,2026 --apply
```

## 검증
```bash
docker exec -it miniwatson-veichle-pg psql -U miniwatson -d miniwatson -c "select count(*) from article_vectors;"
```

## 자주 막히는 지점
- **`Connection refused`(ingest)** = 백엔드(8080)가 안 떠 있음. 3번 먼저.
- **`Port 8080 in use`(백엔드)** = Airflow가 아직 8080. Airflow를 8088로(위).
- **`pgVectorStore init failed / Connection refused 55433`** = pg 컨테이너 다운. `docker compose up -d` 후 healthy 대기.
- **exit 255** = JVM OOM(임베딩 인메모리). `-Xmx4g`↑ 또는 선별 적재. 근본해결은 docs/RAG-INGEST-SCALING.md.

## 비상시 포트 이동 (백엔드를 굳이 옮겨야 할 때)
백엔드를 8090으로 띄우면 ingest도 같이 가리켜야 함:
```bash
VECTOR_STORE=pgvector ./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx4g -Dserver.port=8090"
MW_INGEST_URL=http://localhost:8090/api/data/ingest-file python3 scripts/ingest_existing_manuals.py ... --apply
# 프론트도: BACKEND_URL=http://localhost:8090 npm run dev
```
