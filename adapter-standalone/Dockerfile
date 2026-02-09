# ── Stage 1: Build ───────────────────────────────────────────────────────
# Full JDK for Gradle build.  Only the shadow JAR survives to stage 2.
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Copy Gradle wrapper + build scripts first (layer-cache friendly)
COPY gradle/             gradle/
COPY gradlew             gradlew
COPY build.gradle.kts    build.gradle.kts
COPY settings.gradle.kts settings.gradle.kts
COPY gradle/libs.versions.toml gradle/libs.versions.toml

# Download dependencies (cached unless build files change)
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies || true

# Copy source
COPY core/               core/
COPY adapter-standalone/ adapter-standalone/

# Build shadow JAR (tests skipped — already validated in CI)
RUN ./gradlew --no-daemon :adapter-standalone:shadowJar -x test

# ── Stage 2: Custom JRE via jlink ────────────────────────────────────────
# Build a minimal JRE with only the modules the proxy actually uses.
# Cuts the JRE from ~164 MB to ~60 MB.
FROM eclipse-temurin:21-jdk-alpine AS jre-builder

RUN jlink \
    --add-modules java.base,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.security.jgss,java.sql \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress zip-6 \
    --output /custom-jre


# ── Stage 3: Runtime ─────────────────────────────────────────────────────
# Minimal Alpine + custom JRE (FR-004-29, NFR-004-04: < 150 MB)
FROM alpine:3.23

LABEL maintainer="message-xform" \
    description="message-xform standalone HTTP proxy" \
    org.opencontainers.image.source="https://github.com/message-xform"

# Install only CA certificates (needed for TLS outbound) and wget (for healthcheck)
RUN apk add --no-cache ca-certificates

# Copy custom JRE from jlink stage
ENV JAVA_HOME=/opt/java
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=jre-builder /custom-jre $JAVA_HOME

# Create non-root user for security
RUN addgroup -S proxy && adduser -S proxy -G proxy

WORKDIR /app

# Copy shadow JAR from builder
COPY --from=builder /build/adapter-standalone/build/libs/adapter-standalone-*-SNAPSHOT.jar proxy.jar

# Copy default configuration
COPY adapter-standalone/docker/config.yaml /app/config.yaml

# Volume mount points for specs and profiles (FR-004-31, FR-004-32)
# K8s ConfigMaps mount here; docker run -v ./my-specs:/specs also works.
RUN mkdir -p /specs /profiles && chown -R proxy:proxy /specs /profiles
VOLUME ["/specs", "/profiles"]

# Default env vars — ConfigLoader reads SPECS_DIR, PROFILES_DIR, BACKEND_HOST, etc.
ENV SPECS_DIR=/specs \
    PROFILES_DIR=/profiles

# Expose default proxy port
EXPOSE 9090

# Switch to non-root user
USER proxy

# Health check (FR-004-21)
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -q --spider http://localhost:9090/health || exit 1

# Entrypoint: shadow JAR with JVM tuning for containers
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "proxy.jar"]

# Default args — user can override with docker run ... --config /path
CMD ["--config", "/app/config.yaml"]
