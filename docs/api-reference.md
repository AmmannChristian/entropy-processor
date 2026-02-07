# API Reference

This document specifies the REST and gRPC interfaces exposed by the entropy-processor service. All REST endpoints are versioned under `/api/v1` and produce JSON responses. gRPC services are defined by Protocol Buffer specifications in `src/main/proto/`.

## Table of Contents

1. [REST API](#rest-api)
   1. [Authentication](#authentication)
   2. [Entropy Endpoints](#entropy-endpoints)
   3. [Event Endpoints](#event-endpoints)
   4. [Error Responses](#error-responses)
2. [gRPC Services](#grpc-services)
   1. [EntropyStream Service](#entropystream-service)
   2. [NIST SP 800-22 Service (Client)](#nist-sp-800-22-service-client)
   3. [NIST SP 800-90B Service (Client)](#nist-sp-800-90b-service-client)
   4. [gRPC Health Service](#grpc-health-service)
3. [Protocol Buffer Message Types](#protocol-buffer-message-types)

## REST API

### Authentication

All REST API endpoints under `/api/*` require a valid Bearer token in the `Authorization` header. The token may be either a JWT or an opaque token issued by the configured ZITADEL identity provider. Role-based access is enforced as follows:

| Role | Access Level |
|---|---|
| `ADMIN_ROLE` | Full access to all endpoints |
| `USER_ROLE` | Full access to all endpoints |

Endpoints annotated with `@PermitAll` are accessible without authentication.

### Entropy Endpoints

All entropy endpoints are served by `EntropyResource` at the base path `/api/v1`.

#### GET /api/v1/entropy/shannon

Calculates Shannon entropy from radioactive decay intervals using a histogram-based probability distribution.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `from` | Query (string) | Now minus 1 hour | Start of time window (ISO-8601) |
| `to` | Query (string) | Now | End of time window (ISO-8601) |
| `bucketSize` | Query (int) | 1000000 | Histogram bucket size in nanoseconds (1000000 = 1ms) |

**Response** (200): `ShannonEntropyResponseDTO`

| Field | Type | Description |
|---|---|---|
| `shannonEntropy` | Double | Shannon entropy in bits |
| `sampleCount` | Long | Number of intervals analyzed |
| `windowStart` | Instant | Start of analysis window |
| `windowEnd` | Instant | End of analysis window |
| `bucketSizeNs` | Integer | Histogram bucket size in nanoseconds |

#### GET /api/v1/entropy/renyi

Calculates Renyi entropy with a configurable alpha parameter. When alpha approaches 1.0, the computation falls back to Shannon entropy. Alpha equal to 2.0 yields collision entropy.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `from` | Query (string) | Now minus 1 hour | Start of time window (ISO-8601) |
| `to` | Query (string) | Now | End of time window (ISO-8601) |
| `alpha` | Query (double) | 2.0 | Renyi parameter (must be positive) |
| `bucketSize` | Query (int) | 1000000 | Histogram bucket size in nanoseconds (1000000 = 1ms) |

**Response** (200): `RenyiEntropyResponseDTO`

| Field | Type | Description |
|---|---|---|
| `renyiEntropy` | Double | Renyi entropy in bits |
| `alpha` | Double | Alpha parameter used |
| `sampleCount` | Long | Number of intervals analyzed |
| `windowStart` | Instant | Start of analysis window |
| `windowEnd` | Instant | End of analysis window |
| `bucketSizeNs` | Integer | Histogram bucket size in nanoseconds |

#### GET /api/v1/entropy/comprehensive

Calculates all four entropy measures: Shannon, Renyi (alpha=2), Sample Entropy, and Approximate Entropy.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `from` | Query (string) | Now minus 1 hour | Start of time window (ISO-8601) |
| `to` | Query (string) | Now | End of time window (ISO-8601) |

**Response** (200): `EntropyStatisticsDTO`

| Field | Type | Description |
|---|---|---|
| `shannonEntropy` | Double | Shannon entropy in bits |
| `renyiEntropy` | Double | Renyi entropy in bits (alpha=2.0) |
| `sampleEntropy` | Double | Sample entropy (dimensionless) |
| `approximateEntropy` | Double | Approximate entropy (dimensionless) |
| `sampleCount` | Long | Number of samples |
| `windowStart` | Instant | Analysis window start |
| `windowEnd` | Instant | Analysis window end |
| `processingTimeNs` | Long | Processing time in nanoseconds |
| `basicStats` | Object | Basic statistical measures of the interval data |

#### GET /api/v1/entropy/window

Performs a time-window analysis with all available entropy metrics. Functionally identical to the comprehensive endpoint but with mandatory time window parameters.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `from` | Query (string) | Yes | Start of time window (ISO-8601) |
| `to` | Query (string) | Yes | End of time window (ISO-8601) |

**Response** (200): `EntropyStatisticsDTO` (same schema as comprehensive endpoint)

#### GET /api/v1/entropy/nist/latest

Returns the most recent NIST SP 800-22 test suite results. This endpoint is publicly accessible (`@PermitAll`).

**Response** (200): `NISTSuiteResultDTO`

| Field | Type | Description |
|---|---|---|
| `tests` | List | Individual test results for all 15 NIST tests |
| `totalTests` | Integer | Total number of tests executed |
| `passedTests` | Integer | Number of tests that passed |
| `failedTests` | Integer | Number of tests that failed |
| `overallPassRate` | Double | Overall pass rate (0.0 to 1.0) |
| `uniformityCheck` | Boolean | Whether p-value distribution is uniform |
| `executedAt` | Instant | Timestamp of suite execution |
| `datasetSize` | Long | Size of data sample tested (bits) |
| `dataWindow` | Object | Time window of source data |

#### POST /api/v1/entropy/nist/validate

Manually triggers NIST SP 800-22 and SP 800-90B validation for a specified time window. Requires `ADMIN_ROLE` or `USER_ROLE`.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `from` | Query (string) | Now minus 1 hour | Start of validation window (ISO-8601) |
| `to` | Query (string) | Now | End of validation window (ISO-8601) |

The `Authorization` header bearer token is propagated to the NIST gRPC services for authentication.

**Response** (200): `NISTSuiteResultDTO`

**Response** (503): `ErrorResponseDTO` when the NIST service is unavailable

### Event Endpoints

All event endpoints are served by `EventsResource` at the base path `/api/v1`.

#### GET /api/v1/events/recent

Returns the most recent N entropy events from the radioactive decay source. This endpoint is publicly accessible (`@PermitAll`).

| Parameter | Type | Default | Description |
|---|---|---|---|
| `count` | Query (int) | 100 | Number of recent events to return (maximum 10,000) |

**Response** (200): `RecentEventsResponseDTO`

#### GET /api/v1/events/count

Returns the number of entropy events in a specified time window.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `from` | Query (string) | Now minus 1 hour | Start of time window (ISO-8601) |
| `to` | Query (string) | Now | End of time window (ISO-8601) |

**Response** (200): `EventCountResponseDTO`

#### GET /api/v1/events/statistics

Returns aggregated statistical measures (mean, standard deviation, min, max, median, coefficient of variation) for inter-event intervals in a time window.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `from` | Query (string) | Now minus 1 hour | Start of time window (ISO-8601) |
| `to` | Query (string) | Now | End of time window (ISO-8601) |

**Response** (200): `IntervalStatisticsDTO`

#### GET /api/v1/events/intervals

Returns detailed interval statistics between consecutive decay events. The response schema is identical to the statistics endpoint but emphasizes inter-event interval analysis.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `from` | Query (string) | Now minus 1 hour | Start of time window (ISO-8601) |
| `to` | Query (string) | Now | End of time window (ISO-8601) |

**Response** (200): `IntervalStatisticsDTO`

#### GET /api/v1/events/quality

Returns a comprehensive data quality assessment including packet loss detection, clock drift analysis, and decay rate plausibility for the entropy source.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `from` | Query (string) | Now minus 1 hour | Start of time window (ISO-8601) |
| `to` | Query (string) | Now | End of time window (ISO-8601) |

**Response** (200): `DataQualityReportDTO`

#### GET /api/v1/events/rate

Returns the event rate in Hz with comparison to the expected entropy decay rate of approximately 184 Hz. This endpoint is publicly accessible (`@PermitAll`).

| Parameter | Type | Default | Description |
|---|---|---|---|
| `from` | Query (string) | Now minus 1 hour | Start of time window (ISO-8601) |
| `to` | Query (string) | Now | End of time window (ISO-8601) |

**Response** (200): `EventRateResponseDTO`

### Error Responses

All error responses follow a consistent JSON structure produced by the `GlobalExceptionHandler`:

```json
{
  "code": "ERROR_CODE",
  "message": "Human-readable error description",
  "timestamp": "2024-01-01T00:00:00",
  "path": "/api/v1/endpoint",
  "status": 400
}
```

| HTTP Status | Error Code | Trigger |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Invalid parameters or insufficient data (`ValidationException`) |
| 401 | `UNAUTHORIZED` | Missing or invalid authentication token |
| 403 | `FORBIDDEN` | Insufficient role permissions |
| 404 | `NOT_FOUND` | Resource not found |
| 500 | `INTERNAL_ERROR` | Unhandled exceptions or generic internal failures |
| 503 | `NIST_SERVICE_ERROR` | NIST gRPC service unavailable or call failure (`NistException`) |

## gRPC Services

### EntropyStream Service

Defined in `entropy.proto`, package `entropy`. This is the primary data plane service implemented by `EntropyStreamService`.

```protobuf
service EntropyStream {
  rpc StreamEntropy(stream EntropyBatch) returns (stream Ack);
  rpc SubscribeBatches(SubscriptionRequest) returns (stream EntropyBatch);
  rpc Control(stream ControlMessage) returns (stream ControlMessage);
}
```

#### StreamEntropy

| Property | Value |
|---|---|
| Type | Bidirectional streaming |
| Authentication | `@RolesAllowed("GATEWAY_ROLE")` |
| Input | Stream of `EntropyBatch` |
| Output | Stream of `Ack` |
| Backpressure | Signaled via `Ack.backpressure` when queue exceeds 800 items |
| Buffer Size | 1,000 messages |

#### SubscribeBatches

| Property | Value |
|---|---|
| Type | Server-side streaming |
| Authentication | `@RolesAllowed({"ADMIN_ROLE", "USER_ROLE"})` |
| Input | `SubscriptionRequest` (client ID) |
| Output | Stream of `EntropyBatch` |
| Rate Limit | 20 batches per second per subscriber (default) |

#### Control

| Property | Value |
|---|---|
| Type | Bidirectional streaming |
| Authentication | `@Authenticated` |
| Input | Stream of `ControlMessage` |
| Output | Stream of `ControlMessage` |
| Supported Payloads | `Hello`, `ConfigUpdate`, `HealthReport`, `Ping`/`Pong` |

### NIST SP 800-22 Service (Client)

Defined in `nist_sp800_22.proto`, package `nist.sp800_22.v1`. The entropy-processor service acts as a gRPC client to this external service.

```protobuf
service Sp80022TestService {
  rpc RunTestSuite(Sp80022TestRequest) returns (Sp80022TestResponse);
}
```

The request contains a raw bitstream (minimum recommended length: 1,000,000 bits) and optional per-test configuration overrides. The response contains individual results for up to 15 statistical tests, an overall pass rate, p-value uniformity statistic, and NIST compliance indicator.

### NIST SP 800-90B Service (Client)

Defined in `nist_sp800_90b.proto`, package `nist.sp800_90b.v1`. The entropy-processor service acts as a gRPC client to this external service.

```protobuf
service Sp80090bAssessmentService {
  rpc AssessEntropy(Sp80090bAssessmentRequest) returns (Sp80090bAssessmentResponse);
}
```

The request specifies the sample data, bits per symbol, and assessment modes (IID and/or non-IID). The response provides an overall min-entropy estimate, individual estimator results (Shannon, Collision, Markov, Compression), and a pass/fail status.

### gRPC Health Service

Defined in `grpc_health.proto`, implementing the standard gRPC Health Checking Protocol. Provides `Check` and `Watch` RPCs for service health monitoring.

## Protocol Buffer Message Types

### Core Data Messages

| Message | Description | Key Fields |
|---|---|---|
| `EntropyBatch` | Batch of TDC events from edge gateway | `events`, `metrics`, `batch_timestamp_us`, `batch_sequence`, `source_id`, `checksum`, `compression`, `whitening`, `tests` |
| `TDCEvent` | Single radioactive decay event | `rpi_timestamp_us`, `tdc_timestamp_ps`, `channel`, `delta_ps`, `flags` |
| `EdgeMetrics` | Aggregated edge validation metrics | `quick_shannon_entropy`, `frequency_test_passed`, `runs_test_passed`, `pool_size`, `processing_latency_us`, `bias_ppm` |
| `Ack` | Batch acknowledgment from server | `success`, `received_sequence`, `message`, `missing_sequences`, `backpressure`, `backpressure_reason` |

### Control Messages

| Message | Description | Key Fields |
|---|---|---|
| `ControlMessage` | Envelope for control plane messages | `oneof payload: Hello, ConfigUpdate, HealthReport, Ping, Pong` |
| `Hello` | Gateway handshake | `source_id`, `version`, `meta` |
| `ConfigUpdate` | Gateway configuration parameters | `target_batch_size`, `max_rps`, `rct_window`, `apt_window`, `freq_warn_ppm`, `enable_zstd` |
| `HealthReport` | Gateway health status | `ready`, `healthy`, `pool_size`, `last_ack_latency_us`, `summary` |
| `Ping` / `Pong` | Latency probe | `ts_us` |

### Supporting Types

| Type | Description |
|---|---|
| `WhiteningStats` | Von Neumann debiasing statistics from edge conditioning |
| `TestSummary` | Lightweight statistical test results from edge (frequency p-value, runs p-value, RCT/APT statistics) |
| `KvMeta` | Free-form key-value metadata labels |
| `ChecksumAlgo` | Enum: `CHECKSUM_ALGO_UNSPECIFIED`, `CHECKSUM_SHA256` |
| `SignatureAlgo` | Enum: `SIGNATURE_ALGO_UNSPECIFIED`, `SIGNATURE_ED25519` |
| `Compression` | Enum: `COMPRESSION_NONE`, `COMPRESSION_ZSTD` |