# syntax=docker/dockerfile:1

# ---- Build stage: compile and package with the Maven wrapper ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Dependencies first: this layer is only rebuilt when pom.xml changes, not on every code change.
# The cache mount keeps ~/.m2 between builds without baking it into an image layer.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src src
# Tests run in CI before the image is built (they need Docker for Testcontainers, which isn't available here)
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests

# Split the fat jar into layers, ordered from least to most frequently changed
RUN java -Djarmode=tools -jar target/forkcast-*.jar extract --layers --launcher --destination extracted

# ---- Runtime stage: JRE only, no build tools or source code ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# Never run as root inside the container
RUN groupadd --system forkcast && useradd --system --gid forkcast --no-create-home forkcast

# One image layer per jar layer: a code change only replaces the small "application" layer,
# so pushes and pulls reuse the ~100 MB of unchanged dependencies
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER forkcast
EXPOSE 8080

# Size the heap from the container's memory limit; restart instead of limping on after an OOM
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Exec form: java is PID 1 and receives SIGTERM directly, so `docker stop` triggers Spring's graceful shutdown
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
