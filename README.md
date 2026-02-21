# Entropy Processor

`entropy-processor` is a Quarkus-based backend service (`Java 21`) that receives entropy events from edge gateways over gRPC, persists them in TimescaleDB/PostgreSQL, exposes analysis and operations APIs over REST, and orchestrates external NIST SP 800-22 and SP 800-90B validation services.

The service also includes optional kernel entropy injection (`/dev/random`) and a scheduled entropy-source comparison workflow.

## Purpose

From repository evidence, this module has the following core responsibilities:

1. Ingest `EntropyBatch` streams from edge gateways (`EntropyStreamService`).
2. Validate and map gRPC payloads to persistent entities (`EntropyBatchProcessingService`, `GrpcMappingService`).
3. Persist high-throughput event data and validation results (`EntropyDataPersistenceService`, Panache entities, Flyway schema).
4. Expose REST APIs for entropy metrics, event queries, validation job management, and comparison results (`resource/*`).
5. Run scheduled and on-demand NIST validation pipelines (`NistValidationService`).
6. Provide readiness/liveness checks for database, external NIST services, and kernel writer state (`health/*`).
7. Enforce OIDC-based security for REST and gRPC interfaces (`OidcAuthInterceptor`, `JwtValidationService`, `ZitadelRolesAugmentor`).

## Internal Structure

```text
entropy-processor/
  src/main/java/com/ammann/entropy/
    config/         # CDI producers (executor)
    dto/            # API and service DTO records
    enumeration/    # Domain enums (job state, validation types, source types)
    exception/      # API and domain exception mapping
    health/         # SmallRye Health checks
    interceptor/    # gRPC auth interceptor
    model/          # JPA/Panache entities and query helpers
    properties/     # API path constants
    resource/       # REST resources
    security/       # OIDC role augmentation
    service/        # gRPC service + application services
    startup/        # Startup recovery logic for async jobs
  src/main/proto/   # gRPC and NIST protocol contracts
  src/main/resources/
    application.properties
    db/migration/V1__initial_schema.sql
  docs/
    architecture.md
    api-reference.md
    data-model.md
```

## Interfaces Exposed by This Module

1. REST API (`/api/v1/...`) for entropy statistics, event data access, validation jobs/results, public activity, and entropy comparison operations.
2. gRPC server service `entropy.EntropyStream` with:
   - `StreamEntropy` (bidirectional ingestion)
   - `SubscribeBatches` (server streaming)
   - `Control` (bidirectional control plane)
3. Health/management endpoints via Quarkus management interface (`/q/health/*`, `/q/metrics`, `/q/openapi`).

Detailed interface documentation is in `docs/api-reference.md`.

## Key Integration Points

1. TimescaleDB/PostgreSQL for all persisted event and validation data.
2. External NIST SP 800-22 gRPC service (`Sp80022TestService`).
3. External NIST SP 800-90B gRPC service (`Sp80090bAssessmentService`).
4. OIDC provider (configured for ZITADEL role claims and optional opaque-token introspection).
5. Linux kernel random device for optional entropy mixing (`kernel.entropy.writer.*`).

## Build and Execution

### Prerequisites

1. Java 21
2. Maven Wrapper (`./mvnw` included)
3. Reachable PostgreSQL/TimescaleDB
4. OIDC and NIST services for full feature operation

### Local Development

```bash
cd entropy-processor
./mvnw quarkus:dev
```

### Package

```bash
cd entropy-processor
./mvnw package
```

## Configuration Anchors

Configuration is centralized in `src/main/resources/application.properties`. The main groups are:

1. `quarkus.datasource.*`, `quarkus.hibernate-orm.*`, `quarkus.flyway.*`
2. `quarkus.grpc.server.*`, `quarkus.grpc.clients.*`
3. `quarkus.oidc.*`, `quarkus.oidc-client.*`, `entropy.security.enabled`
4. `nist.sp80022.*`, `nist.sp80090b.*`
5. `kernel.entropy.writer.*`
6. `entropy.comparison.*`

## Documentation Index

1. [Architecture Overview](docs/architecture.md): component boundaries, interaction patterns, and service role separation.
2. [API Reference](docs/api-reference.md): REST and gRPC contracts exposed or consumed by this module.
3. [Data Model](docs/data-model.md): persistent schema, table purposes, and relational boundaries.
