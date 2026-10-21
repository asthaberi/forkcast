# syntax=docker/dockerfile:1

# ---- Build stage: compile and package with the Maven wrapper ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Maven itself, in its own layer, so the layer cache keeps it until the wrapper version changes. (Left in
# ~/.m2, it lived in the cache mount below, which CI's layer cache doesn't keep, so every CI build downloaded
# Maven again, and one network hiccup doing so failed a build.) Retried for the same reason.
ENV MAVEN_USER_HOME=/opt/maven
COPY mvnw ./
COPY .mvn .mvn
RUN for attempt in 1 2 3 4 5; do ./mvnw -q --version > /dev/null && exit 0; sleep $((attempt * 5)); done; exit 1

# Dependencies next: this layer is only rebuilt when pom.xml changes, not on every code change.
# The cache mount keeps ~/.m2 between local builds without baking it into an image layer.
COPY pom.xml ./
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
