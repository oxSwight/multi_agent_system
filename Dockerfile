# ============================================================
# Stage 1: Builder — compile and package the Spring Boot JAR
# ============================================================
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Copy dependency descriptor first to exploit Docker layer caching.
# Dependencies are only re-downloaded when pom.xml changes.
COPY pom.xml .
RUN mvn dependency:go-offline -B -q

# Copy source and build the fat JAR (tests are run in CI, not here).
COPY src ./src
RUN mvn clean package -DskipTests -B -q

# ============================================================
# Stage 2: Runtime — minimal JRE image, non-root user
# ============================================================
FROM eclipse-temurin:21-jre-alpine AS runtime

# Create a dedicated non-root system user/group.
RUN addgroup -S midas && adduser -S midas -G midas

WORKDIR /app

# Copy only the executable JAR from the builder stage.
COPY --from=builder /build/target/midas-d3-0.1.0-SNAPSHOT.jar app.jar

RUN chown midas:midas app.jar

# Durable artifact store — REST-delivered result ZIPs are written here. docker-compose mounts a
# named volume (midas_artifacts) at this path so archives survive container restarts. Created and
# owned by midas while still root, then defaulted via MIDAS_ARTIFACT_DIR for the app to pick up.
RUN mkdir -p /app/artifacts && chown midas:midas /app/artifacts
ENV MIDAS_ARTIFACT_DIR=/app/artifacts

USER midas

EXPOSE 8080

# Use exec form to ensure signals are forwarded correctly (graceful shutdown).
ENTRYPOINT ["java", "-jar", "app.jar"]
