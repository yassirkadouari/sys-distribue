# ============================================================
# Byzantine Consensus Node — Multi-stage Docker Build
# ============================================================
FROM eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /build

# Copy Maven wrapper and POM first for dependency caching
COPY pom.xml .
COPY src ./src

# Install Maven and build
RUN apk add --no-cache maven \
    && mvn clean package -DskipTests -q

# ============================================================
# Runtime stage — minimal image
# ============================================================
FROM eclipse-temurin:25-jre-alpine

LABEL maintainer="Byzantine Consensus Project"
LABEL description="PBFT Consensus Node"

WORKDIR /app

# Copy the fat JAR from the builder
COPY --from=builder /build/target/byzantine-consensus-1.0.0.jar /app/node.jar

# Default environment variables
ENV NODE_ID=0
ENV TOTAL_NODES=4
ENV FAULT_TOLERANCE=1
ENV NODE_PORT=5000
ENV METRICS_PORT=8080
ENV BYZANTINE_TYPE=none
ENV NODE_HOST=0.0.0.0

# Expose consensus and metrics ports
EXPOSE 5000 8080

# Health check
HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
    CMD wget -q --spider http://localhost:${METRICS_PORT}/health || exit 1

# Run the node
ENTRYPOINT ["java", "-jar", "/app/node.jar"]
