# ---- build stage: jar 빌드 ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN chmod +x mvnw && ./mvnw -B -ntp -DskipTests package   # 테스트는 CI 게이트에서 이미 돌림

# ---- run stage: Semeru JRE에서 실행 ----
FROM ibm-semeru-runtimes:open-21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar   # spring-boot repackage된 실행 jar (*.jar.original 제외)
EXPOSE 8080
# Java 21 + Hadoop(parquet) SecurityManager 호환 — spring-boot:run의 jvmArguments와 동일
ENTRYPOINT ["java", "-Djava.security.manager=allow", "-jar", "/app/app.jar"]
