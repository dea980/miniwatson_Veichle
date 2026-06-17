# HotSpot 런타임 전환 (OpenJ9 크래시 회피)

배포 전 1순위 블로커였던 JVM 크래시를 런타임 교체로 해결한 기록. 왜 죽었는지, 무엇을 바꿨는지, 로컬/도커에서 어떻게 돌리고 검증하는지를 담는다.

관련: 배포 전체 흐름은 [CLOUD-DEPLOYMENT.md](CLOUD-DEPLOYMENT.md), 디버깅 일반은 [DEBUGGING.md](DEBUGGING.md).

---

## 1. 무엇이 문제였나

요청 처리 중 JVM이 SIGSEGV로 반복 크래시했다. 루트에 `javacore.*`, `Snap.*`, `jitdump.*` 덤프가 쌓인 게 그 흔적이다.

크래시 시그니처(최신 gpf javacore 기준):

```
1XHEXCPCODE    Signal_Number: 0000000B          (SIGSEGV)
1XHEXCPCODE    InaccessibleAddress: FFFFFFFFFFFFFFF8
1XHEXCPMODULE  Symbol: walkStackFrames
3XMTHREADINFO  "http-nio-8080-exec-5" ...
4XESTACKTRACE   at java/lang/Throwable.fillInStackTrace(Native Method)
                ...
                at java/lang/ArrayIndexOutOfBoundsException.<init>(...)
                at .(Bytecode PC:1770)   (Compiled Code)
```

해석: 앱이 요청 중 `ArrayIndexOutOfBoundsException`을 던지면, OpenJ9가 **JIT 컴파일된 프레임의 스택을 훑어 트레이스를 채우다가**(`fillInStackTrace` → `walkStackFrames`) 잘못된 주소를 건드려 죽는다. `jitdump` 동반과 JIT 스레드 정황까지 합치면 **OpenJ9(Semeru)의 JIT/스택워킹 결함**이 본질이고, 방아쇠는 앱 코드의 AIOOBE다.

근거 대조:

| 런타임 | 결과 |
|---|---|
| OpenJ9 (`ibm-semeru-runtimes:open-21-jre`) | gpf/abort/traceassert javacore 6건 |
| HotSpot (`run-hotspot.log`) | 크래시 마커 0건 |

같은 코드인데 런타임만 다르면 크래시 유무가 갈린다. 따라서 런타임을 HotSpot으로 바꾼다.

---

## 2. 무엇을 바꿨나

`Dockerfile` 런 스테이지의 베이스 이미지만 교체했다. 빌드 스테이지는 이미 Temurin이라 일관성도 유지된다.

```dockerfile
# before
FROM ibm-semeru-runtimes:open-21-jre
# after
FROM eclipse-temurin:21-jre
```

`-Djava.security.manager=allow` ENTRYPOINT는 HotSpot Java 21에서도 동작하므로 유지한다(Hadoop/parquet 호환용).

---

## 3. 어떻게 돌리나

### 3.1 Docker (배포 경로)

```bash
docker build -t miniwatson:hotspot .
docker run --rm -p 8080:8080 miniwatson:hotspot
```

기동 로그에서 런타임 확인:

```bash
docker run --rm miniwatson:hotspot java -version
# OpenJDK Runtime Environment Temurin-21 ... + "OpenJDK 64-Bit Server VM" (HotSpot)
# "Eclipse OpenJ9" 문자열이 안 보여야 정상
```

### 3.2 로컬 개발 (`./mvnw spring-boot:run`)

로컬은 `java`/`JAVA_HOME`이 가리키는 JVM을 쓴다. HotSpot Temurin으로 고정한다.

SDKMAN 사용 시:

```bash
sdk install java 21-tem      # Temurin 21 (HotSpot)
sdk use java 21-tem
java -version                # "OpenJDK 64-Bit Server VM" 확인, "OpenJ9" 아님
./mvnw spring-boot:run
```

Homebrew 사용 시: `brew install --cask temurin@21` 후 `JAVA_HOME`을 해당 경로로 설정.

---

## 4. 검증

1. 기동 후 **크래시 났던 요청을 재현** → `javacore.*`/`Snap.*`/`jitdump.*`가 **새로 안 생기면** 통과.
   ```bash
   ls -t javacore.*.txt 2>/dev/null | head -1   # 재현 후 새 파일 없어야 함
   ```
2. eval 회귀 확인:
   ```bash
   python3 eval/run_eval.py
   ```
3. 기존에 쌓인 덤프는 정리(원인 해결 후):
   ```bash
   rm -f javacore.*.txt Snap.*.trc jitdump.*.dmp
   ```

---

## 5. 남은 일 — 진짜 인덱싱 버그(AIOOBE)

HotSpot 전환은 **크래시를 없앨 뿐**, `ArrayIndexOutOfBoundsException` 자체를 고치는 건 아니다. 다만 HotSpot에선 JVM이 안 죽고 **정상 예외 + 읽을 수 있는 스택 트레이스**로 떠서, 그걸 보고 원인을 잡을 수 있다.

순서:
1. HotSpot으로 기동
2. 크래시 유발 요청 재현 → 로그에서 AIOOBE 스택의 앱 메서드 확인
3. 해당 인덱싱 경계 버그 수정

javacore는 컴파일 프레임이라 메서드명이 안 보여 지금은 특정 불가다. HotSpot 스택이 나오면 이 문서에 원인/수정 내용을 덧붙인다.

---

## 6. OpenJ9를 유지해야 한다면 (대안)

watsonx/Semeru 정체성 때문에 OpenJ9를 꼭 써야 하는 경우의 완화책. 데모엔 권장하지 않으며 HotSpot 전환이 가장 안전하다.

- Semeru를 최신 빌드로 올려 JIT 수정분 반영
- 문제 메서드를 JIT 대상에서 제외: `-Xjit:exclude={메서드시그니처}`
- 최후수단으로 JIT 비활성화 `-Xint` (성능 급락이라 비권장)

---

배포 판단에서 이 항목이 통과돼야(크래시 0) 다음 단계로 간다. [CLOUD-DEPLOYMENT.md](CLOUD-DEPLOYMENT.md) 0번 섹션 참조.
