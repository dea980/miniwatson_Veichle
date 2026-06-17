# CI/CD (Phase 1)

빌드, 테스트, 이미지 배포 자동화 현황과 갭. 정의는 `.github/workflows/ci.yml`. 배포 맥락은 [CLOUD-DEPLOYMENT.md](CLOUD-DEPLOYMENT.md), 런타임 결정은 [HOTSPOT-RUNTIME.md](HOTSPOT-RUNTIME.md).

## 1. 현재 파이프라인

| 잡 | 트리거 | 하는 일 | 게이트 |
|---|---|---|---|
| test | push/PR to main | JDK 21(Temurin)로 단위테스트(`mvnw test`) | 실패 시 이후 중단 |
| build-image | test 통과 + main | 멀티아치(amd64+arm64) 이미지 빌드 → GHCR 푸시(`:latest`, `:<sha>`) | test에 의존 |

흐름: `push main → 단위테스트 → (통과 시) 이미지 빌드/푸시 → (배포는 호스트 측 수동)`.

설계 의도:
- **단위테스트를 게이트로.** 외부 의존(Ollama, pgvector) 없이 도는 테스트만 CI에 둔다. 외부 서비스가 필요한 검증(golden eval, pgvector recall, 인증 라이브)은 CI를 느리고 불안정하게 만들어 분리.
- **멀티아치 빌드.** Oracle Always Free 등 ARM(arm64) 호스트에서도 뜨도록 amd64와 arm64 둘 다 푸시. QEMU 에뮬레이션이라 arm64는 느리지만 호스트 선택을 안 막음.
- **CD는 GHCR까지.** 실제 배포(호스트에서 pull/재기동)는 호스트가 정해진 뒤 연결. 클라우드 미확정 상태와 분리.

## 2. 런타임 일관성 (Temurin)

CI 테스트 JDK를 Semeru(OpenJ9)에서 **Temurin(HotSpot)**으로 맞췄다. 이유: 배포 런타임이 HotSpot(`Dockerfile`의 `eclipse-temurin:21-jre`)인데 CI가 다른 JVM(Semeru)으로 검증하면 "CI는 통과하는데 운영에서 다름"이 생긴다. 게다가 Semeru는 walkStackFrames SIGSEGV로 크래시하던 그 런타임이라([HOTSPOT-RUNTIME.md]) CI에서도 플레이크 위험. **테스트하는 JVM = 배포하는 JVM**로 통일.

## 3. 갭 (다음 보강)

- **eval 게이트가 없다.** golden.json recall은 Ollama가 필요해 현재 CI 밖(수동/nightly). MLOps 관점의 진짜 품질 게이트가 되려면, Ollama를 CI 서비스 컨테이너로 띄우거나(무거움) 소형 임베딩 더미로 대체해 **recall 임계 미달 시 배포 차단**을 넣어야 한다. 왜 중요한가: "테스트는 통과했지만 검색 품질이 떨어진 빌드"를 막는 게 RAG의 회귀 방지 핵심.
- **배포 단계 미연결.** GHCR 푸시 후 호스트 반영이 수동. 호스트 확정 후 SSH 배포나 watchtower류 자동 pull을 연결. (호스트 선택은 [CLOUD-DEPLOYMENT.md])
- **시크릿/스캔.** 이미지 취약점 스캔(docker scout/trivy), 의존성 스캔은 미적용. 운영 전 추가 권장.

## 4. 검증

- PR 올리면 test 잡이 도는지(녹색), main 머지 시 build-image가 GHCR에 푸시하는지 확인.
- 푸시된 이미지가 멀티아치인지: `docker manifest inspect ghcr.io/dea980/miniwatson:latest`에 amd64/arm64 둘 다 보이면 통과.
