# syntax=docker/dockerfile:1

# ---- Build stage: compile and package with the Maven Wrapper --------------------------------
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /workspace

# Resolve dependencies first so this layer is cached until pom.xml changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/job-scheduler-*.jar extract --layers --launcher --destination extracted

# ---- Runtime stage: JRE only, non-root, layered for small incremental pushes ----------------
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app

# Least- to most-frequently changing, so a code change only rebuilds the last layer.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER app
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

# Exec form: the JVM is PID 1 and receives SIGTERM directly, so graceful shutdown (stop polling,
# drain in-flight jobs) runs on `docker stop`.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
