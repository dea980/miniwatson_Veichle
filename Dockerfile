# ---- build stage: jar 빌드 ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN chmod +x mvnw && ./mvnw -B -ntp -DskipTests package \
    && cp target/*.jar app.jar                # 실행 jar을 고정 이름으로 (COPY 글롭 회피)

# ---- run stage: Semeru JRE에서 실행 ----
FROM ibm-semeru-runtimes:open-21-jre
WORKDIR /app
COPY --from=build /app/app.jar app.jar        # 고정 이름이라 buildx 글롭 이슈 없음
EXPOSE 8080
# Java 21 + Hadoop(parquet) SecurityManager 호환 — spring-boot:run의 jvmArguments와 동일
ENTRYPOINT ["java", "-Djava.security.manager=allow", "-jar", "/app/app.jar"]
