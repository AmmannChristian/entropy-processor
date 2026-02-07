# Entropy Processor

Entropy Processor is the central backend microservice of the High Entropy API platform. Built on the Quarkus framework (Java 21), it ingests radioactive decay events from edge gateways via gRPC, persists them in TimescaleDB, performs statistical entropy analysis, and orchestrates NIST SP 800-22 and SP 800-90B validation through external gRPC services. The service also feeds validated entropy into the Linux kernel random pool for consumption by downstream cryptographic applications.

## Table of Contents

1. [Purpose and Scope](#purpose-and-scope)
2. [Key Capabilities](#key-capabilities)
3. [Technology Stack](#technology-stack)
4. [Project Structure](#project-structure)
5. [Prerequisites](#prerequisites)
6. [Build and Run](#build-and-run)
7. [Configuration](#configuration)
8. [Documentation Index](#documentation-index)

## Purpose and Scope

The entropy-processor service occupies the cloud tier of a three-tier architecture for hardware-based random number generation. Strontium-90 (entropy) radioactive decay events are captured by time-to-digital converter (TDC) hardware attached to Raspberry Pi edge gateways. These events are transmitted as gRPC streams to entropy-processor, which is responsible for:

- Receiving, validating, and persisting decay event batches at sustained throughput
- Computing entropy metrics (Shannon, Renyi, Sample Entropy, Approximate Entropy) over configurable time windows
- Delegating formal randomness validation to external NIST SP 800-22 and SP 800-90B services
- Writing validated entropy bytes to the Linux kernel entropy pool (`/dev/random`)
- Exposing REST and gRPC APIs for frontend dashboards, monitoring systems, and administrative tooling
- Broadcasting real-time event streams to subscribed clients

## Key Capabilities

| Capability | Description |
|---|---|
| gRPC Batch Ingestion | Bidirectional streaming with backpressure signaling and per-batch acknowledgment |
| Time-Series Storage | TimescaleDB hypertables with automatic daily chunking for decay events |
| Entropy Computation | Four entropy algorithms operating on inter-event interval distributions |
| NIST Validation | Scheduled and on-demand validation against SP 800-22 (15 randomness tests) and SP 800-90B (min-entropy assessment) |
| Kernel Entropy Writer | Periodic injection of whitened entropy bytes into `/dev/random` via scheduled writes |
| Data Quality Assessment | Packet loss detection, clock drift analysis, and decay rate plausibility checks |
| OIDC Authentication | ZITADEL-based RBAC with JWT and opaque token support, including role augmentation |
| Observability | Prometheus metrics, structured JSON logging, and SmallRye Health readiness probes |

## Technology Stack

| Component | Technology | Version |
|---|---|---|
| Runtime | Quarkus | 3.29.3 |
| Language | Java | 21 |
| Database | TimescaleDB (PostgreSQL) | -- |
| ORM | Hibernate ORM with Panache | Managed by Quarkus BOM |
| Schema Migration | Flyway | Managed by Quarkus BOM |
| RPC Framework | gRPC (Quarkus gRPC) | Managed by Quarkus BOM |
| Identity Provider | ZITADEL (OIDC) | -- |
| Metrics | Micrometer with Prometheus registry | Managed by Quarkus BOM |
| Scheduler | Quartz | Managed by Quarkus BOM |
| Build Tool | Maven | 3.9+ |
| Code Formatting | Spotless (Google Java Format, AOSP style) | 2.43.0 |
| Test Coverage | JaCoCo | 0.8.12 |
| Containerization | Multi-stage Dockerfile (JVM and GraalVM native) | -- |

## Project Structure

```
entropy-processor/
  src/main/
    java/com/ammann/entropy/
      dto/                 # Data Transfer Objects (Java records with OpenAPI annotations)
      exception/           # Application exception hierarchy and global error handler
      health/              # SmallRye Health readiness checks
      model/               # JPA entities extending PanacheEntity
      properties/          # Centralized REST API path constants
      resource/            # JAX-RS REST resources
      security/            # ZITADEL role extraction and identity augmentation
      service/             # Business logic, gRPC service implementation, NIST orchestration
    proto/                 # Protocol Buffer definitions (4 files)
    resources/
      application.properties   # Quarkus configuration with profile overrides
      db/migration/            # Flyway SQL migration scripts
  Dockerfile               # Multi-stage build (dev, JVM, GraalVM native)
  pom.xml                  # Maven project descriptor
```

## Prerequisites

- Java 21 or later (Eclipse Temurin recommended)
- Maven 3.9 or later
- A running TimescaleDB instance (PostgreSQL with the TimescaleDB extension)
- A ZITADEL instance configured as the OIDC identity provider
- (Optional) Access to NIST SP 800-22 and SP 800-90B gRPC services for validation
- (Optional) Docker for containerized deployment

## Build and Run

### Local Development

```bash
./mvnw quarkus:dev \
  -Dquarkus.profile=dev \
  -DOIDC_AUTH_SERVER_URL=https://your-zitadel-instance \
  -DOIDC_CLIENT_ID=your-client-id \
  -DOIDC_JWT_KEY_ID=your-key-id
```

Quarkus dev mode enables hot reload on port 9080 (HTTP) and 9443 (HTTPS/gRPC).

### Production Build (JVM)

```bash
./mvnw package -DskipTests
java -jar target/quarkus-app/quarkus-run.jar
```

### Production Build (Native)

```bash
./mvnw package -Dnative -DskipTests
./target/entropy-processor-1.0-SNAPSHOT-runner
```

### Docker

```bash
# Development image with hot reload
docker build --target dev -t entropy-processor:dev .

# Production JVM image
docker build --target prod -t entropy-processor:prod .

# Production native image
docker build --target prod-native -t entropy-processor:prod-native .
```

## Configuration

All configuration is managed through `src/main/resources/application.properties` with environment variable overrides. Key configuration groups include:

| Group | Environment Variables | Description |
|---|---|---|
| Database | `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | TimescaleDB connection |
| OIDC | `OIDC_AUTH_SERVER_URL`, `OIDC_CLIENT_ID`, `OIDC_JWT_KEY_FILE`, `OIDC_JWT_KEY_ID` | ZITADEL authentication |
| gRPC Server | `GRPC_ENABLE_REFLECTION_SERVICE`, `GRPC_MAX_INBOUND_MESSAGE_SIZE` | Inbound gRPC settings |
| NIST Services | `NIST_SP800_22_HOST`, `NIST_SP800_22_PORT`, `NIST_SP800_90B_HOST`, `NIST_SP800_90B_PORT` | External validation services |
| mTLS | `GRPC_KEY_STORE`, `GRPC_TRUST_STORE`, `HTTP_SSL_CLIENT_AUTH` | Mutual TLS certificates |
| Kernel Writer | `KERNEL_WRITER_ENABLED`, `KERNEL_WRITER_DEVICE` | Linux entropy pool injection |
| Security | `SECURITY_ENABLED` | Master switch for OIDC/RBAC enforcement |

Quarkus profile conventions are used throughout: `%dev` for local development with self-signed certificates and `%prod` for production with Vault-managed certificates.

## Documentation Index

| Document | Description |
|---|---|
| [Architecture Overview](docs/architecture.md) | System architecture, component relationships, and design decisions |
| [API Reference](docs/api-reference.md) | REST endpoints, gRPC services, and interface specifications |
| [Data Model](docs/data-model.md) | Database schema, entity model, and TimescaleDB hypertable design |