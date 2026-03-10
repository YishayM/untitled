# ─── Stage 1: build ───────────────────────────────────────────────────────────
# Uses the full JDK image to compile and package the fat jar.
# Gradle wrapper and source are copied in dependency-friendly order so that
# the layer with downloaded dependencies is cached as long as build files
# don't change.
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /workspace

# Copy only the files Gradle needs to resolve dependencies first.
# These layers are cached until build.gradle.kts or gradle/* change.
COPY gradlew .
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts* ./
RUN ./gradlew dependencies --no-daemon -q || true

# Now copy source and build the fat jar.
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

# ─── Stage 2: runtime ─────────────────────────────────────────────────────────
# Lean JRE-only image. The fat jar is the only artefact copied from the builder.
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Non-root user for defence-in-depth.
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /workspace/build/libs/*.jar app.jar

EXPOSE 8080

# -XX:+UseContainerSupport  — respect cgroup memory/CPU limits (default in JDK 11+,
#                              explicit here for clarity)
# -XX:MaxRAMPercentage=75   — leave 25% headroom for OS/Metaspace/off-heap
# -Djava.security.egd=...   — faster startup on entropy-limited containers
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
