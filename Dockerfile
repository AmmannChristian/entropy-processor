# Multi-Stage Dockerfile for Quarkus Application
# Supports: Development (hot reload), Production (JVM), Native (GraalVM)

# Stage: Maven Base
FROM maven:3.9.9-eclipse-temurin-21 AS maven-base
WORKDIR /build

# Copy Maven wrapper and pom.xml first for better layer caching
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Download dependencies (cached layer if pom.xml unchanged)
RUN ./mvnw dependency:go-offline -B

# Stage: Builder (Production Build)
FROM maven-base AS builder
WORKDIR /build

# Copy source code
COPY src ./src

# Build the application (skip tests and spotless for faster builds, run them in CI/CD)
RUN ./mvnw package -DskipTests -Dspotless.check.skip=true -B

# Stage: Development
# Purpose: Hot reload development with Quarkus dev mode
# Usage: docker build --target dev -t entropy-processor:dev .
FROM maven:3.9.9-eclipse-temurin-21 AS dev

WORKDIR /app

# Install curl for healthchecks
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Copy Maven wrapper and dependencies
COPY --from=maven-base /build/.mvn .mvn
COPY --from=maven-base /build/mvnw ./
COPY --from=maven-base /build/pom.xml ./
COPY --from=maven-base /root/.m2 /root/.m2

# Copy source code (mount as volume for hot reload)
COPY src ./src

# Expose ports
# 9080: HTTP port
# 9443: HTTPS port
# 9090: Management port (health checks)
# 5005: Debug port
EXPOSE 9080 9443 9090 5005

# Environment variables for dev mode
ENV QUARKUS_HTTP_PORT=9080 \
    QUARKUS_HTTP_SSL_PORT=9443 \
    QUARKUS_MANAGEMENT_ENABLED=true \
    QUARKUS_MANAGEMENT_PORT=9090 \
    QUARKUS_HTTP_HOST=0.0.0.0 \
    JAVA_ENABLE_DEBUG=true \
    JAVA_DEBUG_PORT=*:5005

# Health check (uses management port)
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:9090/q/health/live || exit 1

# Run Quarkus in dev mode with hot reload
ENTRYPOINT ["./mvnw", "quarkus:dev", "-Dquarkus.http.host=0.0.0.0", "-Ddebug=5005", "-DdebugHost=0.0.0.0"]

# Stage: Production (JVM Mode)
# Purpose: Optimized production deployment with JVM
# Usage: docker build --target prod -t entropy-processor:prod .
FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:1.23 AS prod

WORKDIR /deployments

# Copy application artifacts from builder
# Note: curl-minimal is already installed in the base image for healthchecks
COPY --from=builder --chown=185:185 /build/target/quarkus-app/lib/ ./lib/
COPY --from=builder --chown=185:185 /build/target/quarkus-app/*.jar ./
COPY --from=builder --chown=185:185 /build/target/quarkus-app/app/ ./app/
COPY --from=builder --chown=185:185 /build/target/quarkus-app/quarkus/ ./quarkus/

# Restrict permissions to app user only (skip the /deployments directory itself)
RUN find /deployments -mindepth 1 -exec chmod u=rwX,go= {} \;

# Switch to non-root user for security
USER 185

# Expose ports
# 9080: HTTP port
# 9443: HTTPS port
# 9090: Management port (health checks)
EXPOSE 9080 9443 9090

# Environment variables for production
ENV LANGUAGE='en_US:en' \
    QUARKUS_HTTP_PORT=9080 \
    QUARKUS_HTTP_SSL_PORT=9443 \
    QUARKUS_MANAGEMENT_ENABLED=true \
    QUARKUS_MANAGEMENT_PORT=9090 \
    JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager" \
    JAVA_APP_JAR="quarkus-run.jar" \
    JAVA_MAX_MEM_RATIO=75 \
    JAVA_INITIAL_MEM_RATIO=50 \
    GC_CONTAINER_OPTIONS="-XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# Health check (uses management port)
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:9090/q/health/live || exit 1

# Run application using optimized run-java script
ENTRYPOINT ["/opt/jboss/container/java/run/run-java.sh"]

# Stage: Native Builder (GraalVM Native Image)
# Purpose: Build native executable for maximum performance
FROM quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-21 AS native-builder

WORKDIR /build

# Copy Maven wrapper and pom.xml
COPY --chown=quarkus:quarkus .mvn/ .mvn/
COPY --chown=quarkus:quarkus mvnw pom.xml ./

# Download dependencies
RUN ./mvnw dependency:go-offline -B

# Copy source code
COPY --chown=quarkus:quarkus src ./src

# Build native executable
RUN ./mvnw package -Dnative -DskipTests -B

# Stage: Native (Production Native Image)
# Purpose: Minimal production image with native executable
# Usage: docker build --target prod-native -t entropy-processor:prod-native .
FROM registry.access.redhat.com/ubi9/ubi-minimal:9.5 AS prod-native

WORKDIR /work

# Install required runtime libraries
RUN microdnf install -y --setopt=install_weak_deps=0 --nodocs curl-minimal && \
    microdnf clean all && \
    rm -rf /var/cache/yum

# Copy native executable
COPY --from=native-builder --chown=1001:1001 /build/target/*-runner /work/application

# Restrict permissions to app user only.
RUN chmod -R u=rwX,go= /work

# Switch to non-root user
USER 1001

# Expose ports
# 9080: HTTP port
# 9443: HTTPS port
# 9090: Management port (health checks)
EXPOSE 9080 9443 9090

# Environment variables
ENV QUARKUS_HTTP_HOST=0.0.0.0 \
    QUARKUS_HTTP_PORT=9080 \
    QUARKUS_HTTP_SSL_PORT=9443 \
    QUARKUS_MANAGEMENT_ENABLED=true \
    QUARKUS_MANAGEMENT_PORT=9090

# Health check (uses management port)
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:9090/q/health/live || exit 1

# Run native executable
ENTRYPOINT ["./application"]