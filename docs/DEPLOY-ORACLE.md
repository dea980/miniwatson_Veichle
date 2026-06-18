# Oracle Always Free + watsonx 배포 (따라 하기)

목표: miniwatson을 Oracle Always Free 박스에 올려 **공개 URL**을 얻는다. LLM은 watsonx(eu-gb), 컴퓨트는 Oracle 무료, 비용 $0. 추론 자체호스팅(Ollama) 없이 `docker-compose.watsonx.yml`로 앱+pgvector만 띄운다.

전제: [WATSONX-SETUP.md]로 watsonx 검증(`generated_text`) 통과 + `.env` 준비 + 코드 push로 GHCR 이미지 빌드.

## 0. 선행 (로컬에서 끝내고 시작)

- watsonx 검증 통과: `curl .../text/generation` → `generated_text`.
- 코드 push → CI가 GHCR 이미지 빌드:
  ```bash
  git add -A && git commit -m "feat: watsonx 제공자 + Oracle 배포 구성" && git push origin main
  ```
  GitHub Actions 끝나면 `ghcr.io/dea980/miniwatson:latest`(멀티아치, arm64 포함) 생성. **GHCR 패키지를 Public으로** 설정(없으면 박스에서 pull 불가): GitHub → Packages → miniwatson → Package settings → Change visibility → Public.

## 1. Oracle 계정 + VM 생성

1. https://www.oracle.com/cloud/free 가입(카드 인증 있으나 Always Free는 청구 안 됨).
2. 콘솔 → **Compute → Instances → Create instance**.
3. 설정:
   - Image: **Ubuntu 22.04** (또는 Oracle Linux)
   - Shape: **Ampere A1 (ARM)**, **Always Free** 한도 내(현재 2 OCPU/12GB). ARM이라 우리 이미지의 arm64가 필요(CI 멀티아치라 OK).
   - **SSH 키**: 공개키 업로드 또는 생성 키 다운로드(접속용).
4. 생성 후 **Public IP** 메모.

## 2. 네트워크 열기 (보안 목록)

VM의 VCN → **Security List**(또는 Network Security Group)에 Ingress 규칙:
- TCP **22** (SSH, 가능하면 내 IP만)
- TCP **80**, **443** (HTTPS)
- **5432(pg), 3000(grafana)는 외부로 열지 말 것** — 내부/프록시 뒤로만.

그리고 Ubuntu 자체 방화벽(iptables)도 열기:
```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save
```

## 3. Docker 설치 (박스에서)

```bash
ssh ubuntu@<PUBLIC_IP>
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu   # 재로그인 후 sudo 없이 docker
# docker compose v2 포함됨: docker compose version
```

## 4. 코드 + .env 배치

```bash
git clone https://github.com/dea980/miniwatson.git
cd miniwatson
# .env 작성 (로컬 .env 참고, 시크릿 채움)
cat > .env <<'EOF'
DB_PASSWORD=<강한 비번>
GRAFANA_PASSWORD=<비번>
LLM_PROVIDER=watsonx
WATSONX_URL=https://eu-gb.ml.cloud.ibm.com
WATSONX_API_KEY=<키>
WATSONX_PROJECT_ID=<watsonx 프로젝트 ID>
WATSONX_MODEL=ibm/granite-3-8b-instruct
WATSONX_EMBED_MODEL=ibm/granite-embedding-278m-multilingual
SECURITY_ENABLED=false
EOF
```

## 5. 기동

```bash
docker compose -f docker-compose.watsonx.yml up -d
docker compose -f docker-compose.watsonx.yml ps     # postgres, app, prometheus, grafana Up
docker compose -f docker-compose.watsonx.yml logs app | grep -i started
```
검증:
```bash
curl -s localhost:8080/api/governance/model-version    # provider=watsonx
# 코퍼스 적재(최초 1회) — watsonx 임베딩 사용
curl -s -X POST localhost:8080/api/data/ingest --data-urlencode "title=Retrieval-augmented generation" --data-urlencode "namespace=default"
curl -s -X POST localhost:8080/api/rag/ask -H 'Content-Type: application/json' -d '{"question":"What is RAG?"}'
```

## 6. HTTPS (Caddy 리버스 프록시)

도메인이 있으면 Caddy가 Let's Encrypt 자동. 없으면 IP로 우선 시연 후 도메인 붙이기.

```bash
# Caddy 컨테이너 한 개로 80/443 → app:8080 프록시 (같은 compose 네트워크에 추가하거나 별도)
# Caddyfile:
#   your.domain { reverse_proxy app:8080 }
```
HTTPS 붙이면 `SECURITY_ENABLED=true` + API 키 주입 검토([SECURITY.md] 7절). `/actuator`는 외부 차단.

## 7. 검증 + 마무리

- 공개: `https://<도메인>` 접속, UI 동작.
- 모니터링: 필요 시 Grafana는 SSH 터널로만(외부 3000 미개방 권장).
- 비용: Oracle Always Free + watsonx Lite = $0. watsonx estimate $0 유지 확인.
- 종료/파기 절차 메모(데모 끝나면 인스턴스 정리).

## 막히는 지점별

- 박스에서 이미지 pull 실패 → GHCR 패키지 Public 확인(0번).
- arm64 실행 안 됨 → CI 멀티아치 빌드 확인(`docker manifest inspect`).
- LLM 응답이 폴백 메시지 → watsonx 한도/프로젝트 확인([WATSONX-SETUP.md]).
- 502/연결 안 됨 → 보안 목록(2번) + 방화벽 포트 확인.
