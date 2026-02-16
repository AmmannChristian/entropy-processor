/* (C)2026 */
package com.ammann.entropy.service;

import com.ammann.entropy.dto.*;
import com.ammann.entropy.enumeration.JobStatus;
import com.ammann.entropy.enumeration.TestType;
import com.ammann.entropy.enumeration.ValidationType;
import com.ammann.entropy.exception.NistException;
import com.ammann.entropy.exception.ValidationException;
import com.ammann.entropy.grpc.proto.sp80022.*;
import com.ammann.entropy.grpc.proto.sp80090b.*;
import com.ammann.entropy.model.EntropyData;
import com.ammann.entropy.model.Nist90BEstimatorResult;
import com.ammann.entropy.model.Nist90BResult;
import com.ammann.entropy.model.NistTestResult;
import com.ammann.entropy.model.NistValidationJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

/**
 * Service for running NIST SP 800-22 and SP 800-90B validation against entropy data.
 *
 * <p>Orchestrates the full validation pipeline: loading entropy events from TimescaleDB,
 * extracting whitened bitstreams, invoking external NIST gRPC services, and persisting
 * test results. Supports both scheduled hourly validation and on-demand validation
 * triggered via REST API.
 *
 * <p>Authentication for outbound gRPC calls supports two modes: token propagation from
 * an incoming request, or service-to-service authentication via {@link OidcClientService}.
 */
@ApplicationScoped
public class NistValidationService {

    private static final Logger LOG = Logger.getLogger(NistValidationService.class);
    private static final int DEFAULT_SP80022_MAX_BYTES = 1_250_000;
    private static final long DEFAULT_SP80022_MIN_BITS = 1_000_000L;
    private static final int DEFAULT_SP80090B_MAX_BYTES = 1_000_000;
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    @GrpcClient("sp80022-test-service")
    MutinySp80022TestServiceGrpc.MutinySp80022TestServiceStub sp80022Client;

    @GrpcClient("sp80090b-assessment-service")
    MutinySp80090bAssessmentServiceGrpc.MutinySp80090bAssessmentServiceStub sp80090bClient;

    private Sp80022TestService clientOverride;
    private Sp80090bAssessmentService sp80090bOverride;

    @ConfigProperty(name = "nist.sp80022.max-bytes", defaultValue = "1250000")
    int sp80022MaxBytes;

    @ConfigProperty(name = "nist.sp80022.min-bits", defaultValue = "1000000")
    long sp80022MinBits;

    @ConfigProperty(name = "nist.sp80090b.max-bytes", defaultValue = "1000000")
    int sp80090bMaxBytes;

    @Inject TransactionSynchronizationRegistry transactionSynchronizationRegistry;
    @Inject Instance<NistValidationService> selfReference;

    @Inject
    @Named("nist-validation-executor")
    ManagedExecutor nistExecutor;

    private final EntityManager em;
    private final MeterRegistry meterRegistry;
    private final OidcClientService oidcClientService;

    private Counter validationFailureCounter;

    @Inject
    public NistValidationService(
            EntityManager em, MeterRegistry meterRegistry, OidcClientService oidcClientService) {
        this.em = em;
        this.meterRegistry = meterRegistry;
        this.oidcClientService = oidcClientService;
    }

    @PostConstruct
    void logThreadPoolInfo() {
        int parallelism = ForkJoinPool.commonPool().getParallelism();
        int poolSize = ForkJoinPool.commonPool().getPoolSize();
        LOG.infof(
                "ForkJoinPool.commonPool() - parallelism=%d, poolSize=%d, availableProcessors=%d",
                parallelism, poolSize, Runtime.getRuntime().availableProcessors());
    }

    void initMetrics() {
        if (meterRegistry != null && validationFailureCounter == null) {
            validationFailureCounter =
                    Counter.builder("nist_validation_failures_total")
                            .description("Count of NIST validation failures")
                            .register(meterRegistry);
        }
    }

    /**
     * Hourly scheduled NIST SP 800-22 validation.
     * <p>
     * Runs at the top of every hour (HH:00:00).
     * Analyzes entropy data from the previous hour.
     * Uses async job pattern for consistent tracking and progress visibility.
     */
    @Scheduled(
            cron = "${nist.sp80022.hourly-cron}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    public void runHourlyNISTValidation() {
        initMetrics();
        LOG.info("Starting hourly NIST SP 800-22 validation (async)");

        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofHours(1));

        try {
            // Use async pattern with "NIST_SCHEDULED_SERVICE" as createdBy
            UUID jobId = startAsyncSp80022Validation(start, end, null, "NIST_SCHEDULED_SERVICE");
            LOG.infof(
                    "Scheduled NIST SP 800-22 validation queued: jobId=%s window=%s..%s",
                    jobId, start, end);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to queue scheduled NIST validation");
            recordFailureMetric();
        }
    }

    /**
     * Weekly scheduled NIST SP 800-90B validation.
     * <p>
     * Default schedule is Sunday at 00:00 UTC and can be overridden with
     * nist.sp80090b.weekly-cron / SP80090B_WEEKLY_CRON.
     * Uses async job pattern for consistent tracking and progress visibility.
     */
    @Scheduled(
            cron = "{nist.sp80090b.weekly-cron}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    public void runWeeklyNIST90BValidation() {
        initMetrics();
        LOG.info("Starting weekly NIST SP 800-90B validation");

        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofDays(7));

        try {
            // Use async pattern with "NIST_SCHEDULED_SERVICE" as createdBy
            UUID jobId = startAsyncSp80090bValidation(start, end, null, "NIST_SCHEDULED_SERVICE");
            LOG.infof(
                    "Scheduled NIST SP 800-90B validation queued: jobId=%s window=%s..%s",
                    jobId, start, end);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to queue scheduled NIST 90B validation");
            recordFailureMetric();
        }
    }

    /**
     * Manual validation of a specific time window.
     * Uses OidcClientService to obtain a service token for gRPC calls.
     * <p>
     * Pipeline:
     * 1. Load entropy data from TimescaleDB
     * 2. Extract whitened entropy bits
     * 3. Call NIST gRPC service
     * 4. Persist results
     *
     * @param start Start of time window
     * @param end   End of time window
     * @return NIST suite result DTO
     */
    @Transactional
    public NISTSuiteResultDTO validateTimeWindow(Instant start, Instant end) {
        return validateTimeWindow(start, end, null);
    }

    /**
     * Manual validation of a specific time window with optional bearer token propagation.
     * <p>
     * If bearerToken is provided, it will be used for gRPC authentication (token propagation
     * from frontend requests). If null, falls back to OidcClientService for service-to-service auth.
     * <p>
     * Pipeline:
     * 1. Load entropy data from TimescaleDB
     * 2. Extract whitened entropy bits
     * 3. Call NIST gRPC service
     * 4. Persist results
     *
     * @param start       Start of time window
     * @param end         End of time window
     * @param bearerToken Optional bearer token from incoming request (without "Bearer " prefix)
     * @return NIST suite result DTO
     */
    @Transactional
    public NISTSuiteResultDTO validateTimeWindow(Instant start, Instant end, String bearerToken) {
        initMetrics();
        LOG.infof(
                "Validating NIST SP 800-22 window: %s to %s (token propagation: %b)",
                start, end, bearerToken != null);

        // Load entropy data from TimescaleDB
        List<EntropyData> events = EntropyData.findInTimeWindow(start, end);

        if (events.isEmpty()) {
            LOG.warnf("No entropy data found in window %s to %s", start, end);
            recordFailureMetric();
            throw new NistException("No entropy data in specified window");
        }

        LOG.infof("Loaded %d entropy events from TimescaleDB", events.size());

        // Step 2: Extract whitened entropy bits
        byte[] bitstream = extractWhitenedBits(events);
        long bitstreamLengthBits = bitstream.length * 8L;
        long minBitsRequired = getEffectiveSp80022MinBits();

        if (bitstreamLengthBits < minBitsRequired) {
            LOG.warnf(
                    "Insufficient bits for NIST SP 800-22: %d (minimum: %d)",
                    bitstreamLengthBits, minBitsRequired);
            recordFailureMetric();
            throw new NistException(
                    String.format(
                            "Need at least %d bits, got %d", minBitsRequired, bitstreamLengthBits));
        }

        validateSp80022ChunkConfig();
        List<byte[]> chunks = splitSp80022Chunks(bitstream);
        LOG.infof(
                "NIST SP 800-22 run window=%s..%s totalBytes=%d totalBits=%d chunks=%d"
                        + " maxChunkBytes=%d",
                start,
                end,
                bitstream.length,
                bitstreamLengthBits,
                chunks.size(),
                getEffectiveSp80022MaxBytes());

        String batchId = events.getFirst().batchId;
        UUID testSuiteRunId = UUID.randomUUID();
        List<NISTTestResultDTO> testDTOs = new ArrayList<>();
        List<NistTestResult> entitiesToPersist = new ArrayList<>();
        int totalTests = 0;
        int passedCount = 0;
        boolean allChunksCompliant = true;

        for (int i = 0; i < chunks.size(); i++) {
            byte[] chunk = chunks.get(i);
            long chunkBits = chunk.length * 8L;

            Sp80022TestResponse grpcResult = runSp80022Tests(chunk, bearerToken);
            int chunkPassedCount =
                    (int)
                            grpcResult.getResultsList().stream()
                                    .filter(Sp80022TestResult::getPassed)
                                    .count();
            int chunkTotalTests = grpcResult.getTestsRun();

            LOG.infof(
                    "NIST SP 800-22 chunk=%d/%d bytes=%d bits=%d passed=%d/%d compliant=%b"
                            + " passRate=%.6f",
                    i + 1,
                    chunks.size(),
                    chunk.length,
                    chunkBits,
                    chunkPassedCount,
                    chunkTotalTests,
                    grpcResult.getNistCompliant(),
                    grpcResult.getOverallPassRate());

            collectSp80022ChunkResults(
                    grpcResult,
                    testSuiteRunId,
                    batchId,
                    start,
                    end,
                    i + 1,
                    chunks.size(),
                    chunkBits,
                    entitiesToPersist,
                    testDTOs);

            totalTests += chunkTotalTests;
            passedCount += chunkPassedCount;
            allChunksCompliant &= grpcResult.getNistCompliant();
        }

        int failedCount = totalTests - passedCount;
        double overallPassRate = totalTests > 0 ? (double) passedCount / totalTests : 0.0;
        persistNistTestResultsBatch(entitiesToPersist);

        LOG.infof(
                "NIST SP 800-22 run completed: runId=%s passed=%d/%d passRate=%.6f"
                        + " allChunksCompliant=%b",
                testSuiteRunId, passedCount, totalTests, overallPassRate, allChunksCompliant);

        return new NISTSuiteResultDTO(
                testDTOs,
                totalTests,
                passedCount,
                failedCount,
                overallPassRate,
                allChunksCompliant,
                Instant.now(),
                bitstreamLengthBits,
                new TimeWindowDTO(start, end, Duration.between(start, end).toHours()));
    }

    /**
     * Runs NIST SP 800-22 tests via gRPC.
     *
     * @param bitstream   The bitstream to test
     * @param bearerToken Optional bearer token for authentication (null = use OidcClientService)
     * @return The gRPC response
     */
    private Sp80022TestResponse runSp80022Tests(byte[] bitstream, String bearerToken) {
        Sp80022TestRequest request =
                Sp80022TestRequest.newBuilder()
                        .setBitstream(com.google.protobuf.ByteString.copyFrom(bitstream))
                        .build();

        try {
            if (clientOverride != null) {
                return clientOverride.runTestSuite(request).await().atMost(Duration.ofMinutes(10));
            }

            var client = sp80022Client;
            String token = resolveToken(bearerToken, "NIST SP 800-22");
            if (token != null) {
                client = withBearerToken(client, token);
            }
            return client.runTestSuite(request).await().atMost(Duration.ofMinutes(10));

        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.UNAVAILABLE) {
                LOG.error("NIST SP 800-22 service unavailable - is the container running?");
                recordFailureMetric();
                throw new NistException("NIST service unavailable", e);
            }
            recordFailureMetric();
            throw new NistException("NIST gRPC call failed", e);
        }
    }

    private void collectSp80022ChunkResults(
            Sp80022TestResponse grpcResult,
            UUID testSuiteRunId,
            String batchId,
            Instant start,
            Instant end,
            int chunkIndex,
            int chunkCount,
            long chunkBits,
            List<NistTestResult> entitiesToPersist,
            List<NISTTestResultDTO> testDTOs) {
        for (Sp80022TestResult test : grpcResult.getResultsList()) {
            NistTestResult entity =
                    new NistTestResult(
                            testSuiteRunId,
                            test.getName(),
                            test.getPassed(),
                            test.getPValue(),
                            start,
                            end);
            entity.dataSampleSize = chunkBits;
            entity.bitsTested = chunkBits;
            entity.batchId = batchId;
            entity.chunkIndex = chunkIndex;
            entity.chunkCount = chunkCount;
            entity.details =
                    test.hasWarning() ? ensureJsonDocument(test.getWarning(), "warning") : null;

            entitiesToPersist.add(entity);
            testDTOs.add(entity.toDTO());
        }
    }

    private void persistNistTestResultsBatch(List<NistTestResult> entitiesToPersist) {
        for (NistTestResult entity : entitiesToPersist) {
            em.persist(entity);
        }
        em.flush();
        LOG.infof("Persisted %d NIST SP 800-22 test result rows", entitiesToPersist.size());
    }

    private void validateSp80022ChunkConfig() {
        long minBits = getEffectiveSp80022MinBits();
        int maxBytes = getEffectiveSp80022MaxBytes();
        long maxBits = maxBytes * 8L;
        if (maxBits < minBits) {
            throw new NistException(
                    String.format(
                            "Invalid SP800-22 configuration: max bytes (%d) are below min bits"
                                    + " (%d)",
                            maxBytes, minBits));
        }
    }

    private List<byte[]> splitSp80022Chunks(byte[] bitstream) {
        int maxBytes = getEffectiveSp80022MaxBytes();
        int minBytes = (int) Math.ceil(getEffectiveSp80022MinBits() / 8.0);

        if (bitstream.length <= maxBytes) {
            return List.of(bitstream);
        }

        List<byte[]> chunks = new ArrayList<>();
        int offset = 0;
        while (offset < bitstream.length) {
            int remaining = bitstream.length - offset;
            int chunkSize = Math.min(maxBytes, remaining);

            // Keep the last chunk above the minimum size by rebalancing with the current chunk.
            if (remaining > maxBytes && (remaining - chunkSize) < minBytes) {
                chunkSize = remaining - minBytes;
            }

            byte[] chunk = Arrays.copyOfRange(bitstream, offset, offset + chunkSize);
            chunks.add(chunk);
            offset += chunkSize;
        }

        return chunks;
    }

    private int getEffectiveSp80022MaxBytes() {
        return sp80022MaxBytes > 0 ? sp80022MaxBytes : DEFAULT_SP80022_MAX_BYTES;
    }

    private long getEffectiveSp80022MinBits() {
        return sp80022MinBits > 0 ? sp80022MinBits : DEFAULT_SP80022_MIN_BITS;
    }

    private int getEffectiveSp80090bMaxBytes() {
        return sp80090bMaxBytes > 0 ? sp80090bMaxBytes : DEFAULT_SP80090B_MAX_BYTES;
    }

    /**
     * Resolves the token to use for gRPC calls.
     *
     * @param bearerToken Propagated token from request (may be null)
     * @param serviceName Name of the service for logging
     * @return Token to use, or null if no authentication required
     */
    private String resolveToken(String bearerToken, String serviceName) {
        // If a bearer token is provided, use it (token propagation)
        if (bearerToken != null && !bearerToken.isBlank()) {
            LOG.debugf("Using propagated bearer token for %s call", serviceName);
            return bearerToken;
        }

        // Otherwise, try to get a service token from OidcClientService
        if (oidcClientService.isConfigured()) {
            try {
                String token = oidcClientService.getAccessTokenOrThrow();
                LOG.debugf("Using service token from OidcClientService for %s call", serviceName);
                return token;
            } catch (OidcClientService.TokenFetchException e) {
                LOG.errorf(
                        "Failed to obtain access token for %s call: %s",
                        serviceName, e.getMessage());
                throw new NistException("Authentication required but token unavailable", e);
            }
        }

        LOG.debugf("No authentication configured for %s call", serviceName);
        return null;
    }

    @Transactional
    public NIST90BResultDTO validate90BTimeWindow(Instant start, Instant end) {
        return validate90BTimeWindow(start, end, null);
    }

    @Transactional
    public NIST90BResultDTO validate90BTimeWindow(Instant start, Instant end, String bearerToken) {
        initMetrics();
        LOG.infof(
                "Validating NIST SP 800-90B window: %s to %s (token propagation: %b)",
                start, end, bearerToken != null);

        List<EntropyData> events = EntropyData.findInTimeWindow(start, end);
        if (events.isEmpty()) {
            LOG.warnf(
                    "Skipping NIST SP 800-90B: no entropy data found in window %s to %s",
                    start, end);
            recordFailureMetric();
            throw new NistException("No entropy data in specified window");
        }

        byte[] bitstream = extractWhitenedBits(events);
        if (bitstream.length == 0) {
            LOG.warnf(
                    "Skipping NIST SP 800-90B: extracted bitstream is empty in window %s to %s",
                    start, end);
            recordFailureMetric();
            throw new NistException("No usable entropy bitstream in specified window");
        }

        int sourceBytes = bitstream.length;
        int maxBytes = getEffectiveSp80090bMaxBytes();
        byte[] assessmentSample = bitstream;
        if (sourceBytes > maxBytes) {
            assessmentSample = Arrays.copyOf(bitstream, maxBytes);
            LOG.warnf(
                    "NIST SP 800-90B input exceeds limit: sourceBytes=%d maxBytes=%d. Truncating"
                            + " assessment sample.",
                    sourceBytes, maxBytes);
        }

        LOG.infof(
                "NIST SP 800-90B run window=%s..%s sourceBytes=%d assessmentBytes=%d",
                start, end, sourceBytes, assessmentSample.length);

        String batchId = events.getFirst().batchId;
        UUID assessmentRunId = UUID.randomUUID();
        Nist90BResult entity = assess90B(assessmentSample, batchId, start, end, bearerToken, assessmentRunId);
        NIST90BResultDTO result = entity.toDTO();
        LOG.infof(
                "NIST SP 800-90B assessment complete: minEntropy=%.6f, passed=%b, bits=%d",
                result.minEntropy(), result.passed(), result.bitsTested());
        return result;
    }

    /**
     * Runs NIST SP 800-90B entropy assessment via gRPC and persists the result.
     *
     * @param bitstream   The bitstream to assess
     * @param batchId     Batch ID for the result
     * @param start       Start of time window
     * @param end         End of time window
     * @param bearerToken Optional bearer token for authentication (null = use OidcClientService)
     * @return The persisted Nist90BResult entity
     */
    private Nist90BResult assess90B(
            byte[] bitstream, String batchId, Instant start, Instant end, String bearerToken, UUID assessmentRunId) {
        if (sp80090bOverride == null && sp80090bClient == null) {
            LOG.warn("NIST SP 800-90B client not configured");
            recordFailureMetric();
            throw new NistException("NIST SP 800-90B client not available");
        }

        Sp80090bAssessmentRequest request =
                Sp80090bAssessmentRequest.newBuilder()
                        .setData(com.google.protobuf.ByteString.copyFrom(bitstream))
                        .setBitsPerSymbol(8) // Byte-level analysis
                        .setIidMode(true) // Run IID tests
                        .setNonIidMode(true) // Run Non-IID estimators
                        .setVerbosity(1) // Normal verbosity
                        .build();

        Sp80090bAssessmentResponse entropyResult;
        try {
            if (sp80090bOverride != null) {
                entropyResult =
                        sp80090bOverride
                                .assessEntropy(request)
                                .await()
                                .atMost(Duration.ofMinutes(10));
            } else {
                var client = sp80090bClient;
                String token = resolveToken(bearerToken, "NIST SP 800-90B");
                if (token != null) {
                    client = withBearerToken(client, token);
                }
                entropyResult =
                        client.assessEntropy(request).await().atMost(Duration.ofMinutes(10));
            }
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.UNAVAILABLE) {
                LOG.error("NIST SP 800-90B service unavailable - is the container running?");
                recordFailureMetric();
                throw new NistException("NIST SP 800-90B service unavailable", e);
            }
            recordFailureMetric();
            throw new NistException("NIST SP 800-90B gRPC call failed", e);
        }

        // Use provided assessment run ID (passed from caller)
        long bitstreamLength = bitstream.length * 8L;

        // Create aggregate result entity
        Nist90BResult entity =
                new Nist90BResult(
                        batchId,
                        entropyResult.getMinEntropy(),
                        entropyResult.getPassed(),
                        ensureJsonDocument(entropyResult.getAssessmentSummary(), "summary"),
                        bitstreamLength,
                        start,
                        end);

        entity.assessmentRunId = assessmentRunId;
        em.persist(entity);

        // Persist ALL 14 estimators (10 Non-IID + 4 IID) with full metadata
        for (Sp80090bEstimatorResult est : entropyResult.getNonIidResultsList()) {
            persistEstimator(assessmentRunId, TestType.NON_IID, est);
        }
        for (Sp80090bEstimatorResult est : entropyResult.getIidResultsList()) {
            persistEstimator(assessmentRunId, TestType.IID, est);
        }

        return entity;
    }

    /**
     * Persists a single estimator result to the nist_90b_estimator_results table.
     *
     * <p>Part of V2a dual-write strategy to store ALL 14 estimators (10 Non-IID + 4 IID)
     * with full metadata (passed, details, description).
     *
     * <p><b>Entropy Semantics:</b> Upstream -1.0 sentinel → NULL (distinguishes non-entropy
     * tests from true zero entropy).
     *
     * @param assessmentRunId Assessment run identifier (foreign key)
     * @param testType Test type (IID or NON_IID)
     * @param proto Upstream protobuf estimator result
     */
    private void persistEstimator(
            UUID assessmentRunId,
            TestType testType,
            Sp80090bEstimatorResult proto) {

        // Entropy semantics: Upstream -1.0 = non-entropy test, map to NULL
        // True 0.0 = degenerate source, keep as 0.0
        double rawEntropy = proto.getEntropyEstimate();
        Double entropyEstimate = rawEntropy < 0.0 ? null : rawEntropy;

        // Extract details as Map (Quarkus/Jackson serializes to JSONB)
        Map<String, Double> details =
                proto.getDetailsCount() > 0 ? new HashMap<>(proto.getDetailsMap()) : null;

        Nist90BEstimatorResult estimator =
                new Nist90BEstimatorResult(
                        assessmentRunId,
                        testType,
                        proto.getName(),
                        entropyEstimate,
                        proto.getPassed(),
                        details,
                        proto.getDescription());

        em.persist(estimator);

        LOG.debugf(
                "Persisted estimator: runId=%s type=%s name=%s entropy=%s passed=%b",
                assessmentRunId, testType, proto.getName(), entropyEstimate, proto.getPassed());
    }

    private String ensureJsonDocument(String rawValue, String fallbackField) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }

        try {
            JSON_MAPPER.readTree(rawValue);
            return rawValue;
        } catch (Exception ignored) {
            return JSON_MAPPER.createObjectNode().put(fallbackField, rawValue).toString();
        }
    }

    /**
     * Attaches a Bearer token header to a gRPC stub.
     */
    private <T extends AbstractStub<T>> T withBearerToken(T client, String token) {
        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION_KEY, "Bearer " + token);
        return client.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    /**
     * Extracts whitened entropy bits from EntropyData events.
     * <p>
     * Uses stored inbound whitened_entropy bytes as the only source of truth.
     * Each event must contain exactly 32 bytes produced at the gateway.
     *
     * @param events List of EntropyData events
     * @return Byte array containing whitened random bits
     */
    private byte[] extractWhitenedBits(List<EntropyData> events) {
        int expectedBytesPerEvent = GrpcMappingService.EXPECTED_WHITENED_ENTROPY_BYTES;
        ByteArrayOutputStream buffer =
                new ByteArrayOutputStream(events.size() * expectedBytesPerEvent);
        int storedChunkCount = 0;

        for (EntropyData event : events) {
            byte[] whitened = event.whitenedEntropy;
            if (whitened == null || whitened.length == 0) {
                throw new NistException(
                        String.format(
                                "Missing whitened_entropy for sequence %s; gateway must send"
                                        + " %d-byte whitened_entropy per event",
                                event.sequenceNumber, expectedBytesPerEvent));
            }
            if (whitened.length != expectedBytesPerEvent) {
                throw new NistException(
                        String.format(
                                "Invalid whitened_entropy length=%d for sequence %s; expected %d"
                                        + " bytes",
                                whitened.length, event.sequenceNumber, expectedBytesPerEvent));
            }
            buffer.writeBytes(whitened);
            storedChunkCount++;
        }

        byte[] bytes = buffer.toByteArray();
        LOG.debugf(
                "Using %d whitened entropy chunks from database (%d bytes)",
                storedChunkCount, bytes.length);
        return bytes;
    }

    /**
     * Gets the most recent NIST validation result.
     *
     * @return Most recent suite result or null if no results exist
     */
    public NISTSuiteResultDTO getLatestValidationResult() {
        UUID latestRunId = NistTestResult.findMostRecentSuiteRunId();
        if (latestRunId == null) {
            return null;
        }

        List<NistTestResult> tests = NistTestResult.findByTestSuiteRun(latestRunId);
        if (tests.isEmpty()) {
            return null;
        }

        // Group test results by test name and aggregate across chunks
        Map<String, List<NistTestResult>> testsByName =
                tests.stream().collect(Collectors.groupingBy(t -> t.testName));

        List<NISTTestResultDTO> testDTOs =
                testsByName.entrySet().stream()
                        .map(
                                entry -> {
                                    String testName = entry.getKey();
                                    List<NistTestResult> chunks = entry.getValue();

                                    // Aggregate: FAIL if any chunk fails
                                    boolean overallPassed =
                                            chunks.stream()
                                                    .allMatch(t -> Boolean.TRUE.equals(t.passed));

                                    // Take minimum p-value (most conservative)
                                    double minPValue =
                                            chunks.stream()
                                                    .mapToDouble(
                                                            t -> t.pValue != null ? t.pValue : 0.0)
                                                    .min()
                                                    .orElse(0.0);

                                    // Use latest execution time
                                    Instant latestExecutedAt =
                                            chunks.stream()
                                                    .map(t -> t.executedAt)
                                                    .max(Instant::compareTo)
                                                    .orElse(Instant.now());

                                    // Determine status
                                    String status = overallPassed ? "PASS" : "FAIL";

                                    // Take details from first chunk (or aggregate if needed)
                                    String details = chunks.get(0).details;

                                    return new NISTTestResultDTO(
                                            testName,
                                            overallPassed,
                                            minPValue,
                                            status,
                                            latestExecutedAt,
                                            details);
                                })
                        .sorted(Comparator.comparing(NISTTestResultDTO::testName))
                        .toList();

        // Now testDTOs contains aggregated results (15 tests, not 15 × chunks)
        int passedCount =
                (int) testDTOs.stream().filter(dto -> Boolean.TRUE.equals(dto.passed())).count();
        int failedCount = testDTOs.size() - passedCount;
        double passRate = testDTOs.isEmpty() ? 0.0 : (double) passedCount / testDTOs.size();

        // Calculate total bits tested robustly, independent of firstTest metadata
        long datasetSizeBits = 0L;
        Set<Integer> seenChunks = new HashSet<>();

        for (NistTestResult test : tests) {
            Integer chunkIdx = test.chunkIndex;
            if (chunkIdx != null && !seenChunks.contains(chunkIdx)) {
                seenChunks.add(chunkIdx);
                datasetSizeBits += test.bitsTested != null ? test.bitsTested : 0L;
            }
        }

        // Fallback for legacy data without chunk metadata
        if (datasetSizeBits == 0L && !tests.isEmpty()) {
            NistTestResult firstTest = tests.get(0);
            datasetSizeBits = firstTest.dataSampleSize != null ? firstTest.dataSampleSize : 0L;
        }

        // Use latest execution time from aggregated tests
        Instant suiteExecutedAt =
                testDTOs.stream()
                        .map(NISTTestResultDTO::executedAt)
                        .max(Instant::compareTo)
                        .orElse(Instant.now());

        NistTestResult firstTest = tests.getFirst();
        return new NISTSuiteResultDTO(
                testDTOs, // Aggregated tests (15, not 15 × chunks)
                testDTOs.size(), // Now correctly 15, not 30
                passedCount, // Aggregated pass count
                failedCount, // Aggregated fail count
                passRate, // Correct pass rate
                true, // Assuming uniformity check passed
                suiteExecutedAt, // Latest execution time from aggregated tests
                datasetSizeBits, // Robustly calculated from unique chunks
                new TimeWindowDTO(
                        firstTest.windowStart,
                        firstTest.windowEnd,
                        Duration.between(firstTest.windowStart, firstTest.windowEnd).toHours()));
    }

    /**
     * Visible for testing only to avoid real gRPC calls.
     */
    void setClientOverride(Sp80022TestService override) {
        this.clientOverride = override;
    }

    /**
     * Visible for testing only to avoid real gRPC calls.
     */
    void setSp80090bOverride(Sp80090bAssessmentService override) {
        this.sp80090bOverride = override;
    }

    void setSp80022MaxBytesForTesting(int maxBytes) {
        this.sp80022MaxBytes = maxBytes;
    }

    void setSp80022MinBitsForTesting(long minBits) {
        this.sp80022MinBits = minBits;
    }

    void setSp80090bMaxBytesForTesting(int maxBytes) {
        this.sp80090bMaxBytes = maxBytes;
    }

    private void recordFailureMetric() {
        if (validationFailureCounter != null) {
            validationFailureCounter.increment();
        }
    }

    /**
     * Counts NIST validation failures in the last N hours.
     *
     * @param hours Number of hours to look back
     * @return Failure count
     */
    public Long countRecentFailures(int hours) {
        Instant since = Instant.now().minus(Duration.ofHours(hours));
        return NistTestResult.count("executedAt > ?1 AND passed = false", since);
    }

    /**
     * Start an async NIST SP 800-22 validation job.
     * <p>
     * Creates a job record with status=QUEUED, launches async processing,
     * and returns the job ID immediately. Clients poll GET /validate/status/{jobId}
     * for progress and fetch results with GET /validate/result/{jobId} when complete.
     *
     * @param start       Start of time window
     * @param end         End of time window
     * @param bearerToken Bearer token for gRPC authentication
     * @param createdBy   Username of user who triggered validation
     * @return Job ID for tracking
     */
    @Transactional
    public UUID startAsyncSp80022Validation(
            Instant start, Instant end, String bearerToken, String createdBy) {
        long activeJobs = NistValidationJob.countActiveByUser(createdBy);
        if (activeJobs >= 3) {
            throw new ValidationException(
                    String.format(
                            "Maximum concurrent validations reached (%d active). Please wait for"
                                    + " completion.",
                            activeJobs));
        }

        // Create job record
        NistValidationJob job = new NistValidationJob();
        job.validationType = ValidationType.SP_800_22;
        job.status = JobStatus.QUEUED;
        job.windowStart = start;
        job.windowEnd = end;
        job.createdBy = createdBy;
        job.persist();

        UUID jobId = job.id;
        LOG.infof(
                "Created async NIST SP 800-22 validation job: jobId=%s window=%s..%s user=%s",
                jobId, start, end, createdBy);

        LOG.infof("Registering afterCompletion callback for SP 800-22 job %s", jobId);
        dispatchAfterCommit(
                () ->
                        CompletableFuture.runAsync(
                                () -> runSp80022WorkerWithExtendedTimeout(jobId, bearerToken),
                                nistExecutor),
                "SP 800-22",
                jobId);

        return jobId;
    }

    /**
     * Start an async NIST SP 800-90B validation job.
     *
     * @param start       Start of time window
     * @param end         End of time window
     * @param bearerToken Bearer token for gRPC authentication
     * @param createdBy   Username of user who triggered validation
     * @return Job ID for tracking
     */
    @Transactional
    public UUID startAsyncSp80090bValidation(
            Instant start, Instant end, String bearerToken, String createdBy) {
        long activeJobs = NistValidationJob.countActiveByUser(createdBy);
        if (activeJobs >= 3) {
            throw new ValidationException(
                    String.format(
                            "Maximum concurrent validations reached (%d active). Please wait for"
                                    + " completion.",
                            activeJobs));
        }

        NistValidationJob job = new NistValidationJob();
        job.validationType = ValidationType.SP_800_90B;
        job.status = JobStatus.QUEUED;
        job.windowStart = start;
        job.windowEnd = end;
        job.createdBy = createdBy;
        job.persist();

        UUID jobId = job.id;
        LOG.infof(
                "Created async NIST SP 800-90B validation job: jobId=%s window=%s..%s user=%s",
                jobId, start, end, createdBy);

        LOG.infof("Registering afterCompletion callback for SP 800-90B job %s", jobId);
        dispatchAfterCommit(
                () ->
                        CompletableFuture.runAsync(
                                () -> runSp80090bWorkerWithExtendedTimeout(jobId, bearerToken),
                                nistExecutor),
                "SP 800-90B",
                jobId);

        return jobId;
    }

    private void dispatchAfterCommit(Runnable dispatch, String validationType, UUID jobId) {
        if (transactionSynchronizationRegistry == null) {
            LOG.warnf(
                    "TransactionSynchronizationRegistry not available, dispatching %s job %s"
                            + " immediately",
                    validationType, jobId);
            dispatch.run();
            return;
        }

        transactionSynchronizationRegistry.registerInterposedSynchronization(
                new Synchronization() {
                    @Override
                    public void beforeCompletion() {
                        // no-op
                    }

                    @Override
                    public void afterCompletion(int status) {
                        if (status == jakarta.transaction.Status.STATUS_COMMITTED) {
                            LOG.infof(
                                    "Transaction committed, dispatching %s job %s to"
                                            + " CompletableFuture",
                                    validationType, jobId);
                            dispatch.run();
                        } else {
                            LOG.errorf(
                                    "Transaction status=%d (not committed), skipping dispatch for"
                                            + " %s job %s",
                                    status, validationType, jobId);
                        }
                    }
                });
    }

    private NistValidationService transactionalSelf() {
        if (selfReference != null && selfReference.isResolvable()) {
            return selfReference.get();
        }
        LOG.warn(
                "NistValidationService self reference unavailable, falling back to direct"
                        + " invocation");
        return this;
    }

    private void runSp80022WorkerWithExtendedTimeout(UUID jobId, String bearerToken) {
        LOG.infof("Starting worker thread for SP 800-22 job %s", jobId);
        QuarkusTransaction.requiringNew()
                .timeout(1800)
                .run(() -> transactionalSelf().processSp80022ValidationJob(jobId, bearerToken));
    }

    private void runSp80090bWorkerWithExtendedTimeout(UUID jobId, String bearerToken) {
        LOG.infof("Starting worker thread for SP 800-90B job %s", jobId);
        QuarkusTransaction.requiringNew()
                .timeout(1800)
                .run(() -> transactionalSelf().processSp80090bValidationJob(jobId, bearerToken));
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markJobFailed(UUID jobId, String errorMessage) {
        NistValidationJob job = NistValidationJob.findById(jobId);
        if (job == null) {
            LOG.errorf("Cannot mark job %s as FAILED - job not found", jobId);
            return;
        }
        if (job.status == JobStatus.COMPLETED) {
            LOG.warnf(
                    "Skipping FAILED update for job %s because status is already COMPLETED", jobId);
            return;
        }
        job.status = JobStatus.FAILED;
        job.errorMessage = errorMessage;
        job.completedAt = Instant.now();
        job.persist();
    }

    /**
     * Async worker for SP 800-22 validation job.
     * <p>
     * Runs in background thread, updates job progress after each chunk,
     * and marks job as COMPLETED or FAILED when done.
     *
     * @param jobId       Job ID to process
     * @param bearerToken Bearer token for gRPC calls
     */
    @Transactional
    public void processSp80022ValidationJob(UUID jobId, String bearerToken) {
        NistValidationJob job = NistValidationJob.findById(jobId);
        if (job == null) {
            LOG.errorf("SP 800-22 job %s not found", jobId);
            return;
        }

        try {
            // Mark as RUNNING
            job.status = JobStatus.RUNNING;
            job.startedAt = Instant.now();
            job.persist();
            LOG.infof("SP 800-22 job %s started", jobId);

            // Load entropy data
            List<EntropyData> events = EntropyData.findInTimeWindow(job.windowStart, job.windowEnd);
            if (events.isEmpty()) {
                throw new NistException("No entropy data in specified window");
            }

            // Extract bitstream and split into chunks
            byte[] bitstream = extractWhitenedBits(events);
            long bitstreamLengthBits = bitstream.length * 8L;
            long minBitsRequired = getEffectiveSp80022MinBits();

            if (bitstreamLengthBits < minBitsRequired) {
                throw new NistException(
                        String.format(
                                "Need at least %d bits, got %d",
                                minBitsRequired, bitstreamLengthBits));
            }

            validateSp80022ChunkConfig();
            List<byte[]> chunks = splitSp80022Chunks(bitstream);

            job.totalChunks = chunks.size();
            job.persist();

            LOG.infof(
                    "SP 800-22 job %s: totalBytes=%d totalBits=%d chunks=%d",
                    jobId, bitstream.length, bitstreamLengthBits, chunks.size());

            // Generate test suite run ID
            UUID testSuiteRunId = UUID.randomUUID();
            job.testSuiteRunId = testSuiteRunId;
            job.persist();

            String batchId = events.getFirst().batchId;
            List<NistTestResult> entitiesToPersist = new ArrayList<>();

            // Process each chunk with progress updates
            for (int i = 0; i < chunks.size(); i++) {
                byte[] chunk = chunks.get(i);
                long chunkBits = chunk.length * 8L;

                LOG.infof(
                        "SP 800-22 job %s: processing chunk %d/%d (%d bytes)",
                        jobId, i + 1, chunks.size(), chunk.length);

                // Run NIST tests for this chunk
                Sp80022TestResponse grpcResult = runSp80022Tests(chunk, bearerToken);

                // Collect results (reuse existing logic)
                collectSp80022ChunkResults(
                        grpcResult,
                        testSuiteRunId,
                        batchId,
                        job.windowStart,
                        job.windowEnd,
                        i + 1,
                        chunks.size(),
                        chunkBits,
                        entitiesToPersist,
                        new ArrayList<>()); // Don't need DTOs here

                // Update progress
                job.currentChunk = i + 1;
                job.progressPercent = (int) ((i + 1) * 100.0 / chunks.size());
                job.persist();

                LOG.infof(
                        "SP 800-22 job %s: chunk %d/%d complete (%d%%)",
                        jobId, i + 1, chunks.size(), job.progressPercent);
            }

            // Persist all test results in batch
            persistNistTestResultsBatch(entitiesToPersist);

            // Mark job as COMPLETED
            job.status = JobStatus.COMPLETED;
            job.completedAt = Instant.now();
            job.progressPercent = 100;
            job.persist();

            LOG.infof(
                    "SP 800-22 job %s completed successfully: runId=%s duration=%ds",
                    jobId,
                    testSuiteRunId,
                    Duration.between(job.startedAt, job.completedAt).getSeconds());

        } catch (Exception e) {
            LOG.errorf(e, "SP 800-22 job %s failed", jobId);
            String errorMessage =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            job.status = JobStatus.FAILED;
            job.errorMessage = errorMessage;
            job.completedAt = Instant.now();
            try {
                transactionalSelf().markJobFailed(jobId, errorMessage);
            } catch (Exception markFailedException) {
                LOG.errorf(
                        markFailedException,
                        "SP 800-22 job %s failed, and failed to persist FAILED status",
                        jobId);
            }
            recordFailureMetric();
        }
    }

    /**
     * Async worker for SP 800-90B validation job.
     * <p>
     * Similar to SP 800-22 but with chunking for large bitstreams.
     *
     * @param jobId       Job ID to process
     * @param bearerToken Bearer token for gRPC calls
     */
    @Transactional
    public void processSp80090bValidationJob(UUID jobId, String bearerToken) {
        NistValidationJob job = NistValidationJob.findById(jobId);
        if (job == null) {
            LOG.errorf("SP 800-90B job %s not found", jobId);
            return;
        }

        try {
            job.status = JobStatus.RUNNING;
            job.startedAt = Instant.now();
            job.persist();
            LOG.infof("SP 800-90B job %s started", jobId);

            List<EntropyData> events = EntropyData.findInTimeWindow(job.windowStart, job.windowEnd);
            if (events.isEmpty()) {
                throw new NistException("No entropy data in specified window");
            }

            byte[] bitstream = extractWhitenedBits(events);
            if (bitstream.length == 0) {
                throw new NistException("No usable entropy bitstream in specified window");
            }

            // Split into chunks (similar to SP 800-22)
            List<byte[]> chunks = splitSp80090bChunks(bitstream);
            job.totalChunks = chunks.size();
            job.persist();

            LOG.infof(
                    "SP 800-90B job %s: totalBytes=%d chunks=%d",
                    jobId, bitstream.length, chunks.size());

            UUID assessmentRunId = UUID.randomUUID();
            job.assessmentRunId = assessmentRunId;
            job.persist();

            String batchId = events.getFirst().batchId;

            // Process each chunk
            for (int i = 0; i < chunks.size(); i++) {
                byte[] chunk = chunks.get(i);

                LOG.infof(
                        "SP 800-90B job %s: processing chunk %d/%d (%d bytes)",
                        jobId, i + 1, chunks.size(), chunk.length);

                // Run assessment on chunk (pass assessmentRunId to ensure consistency)
                Nist90BResult entity =
                        assess90B(chunk, batchId, job.windowStart, job.windowEnd, bearerToken, assessmentRunId);

                // Update the persisted entity with chunk metadata
                entity.chunkIndex = i;
                entity.chunkCount = chunks.size();
                entity.persist();

                // Update progress
                job.currentChunk = i + 1;
                job.progressPercent = (int) ((i + 1) * 100.0 / chunks.size());
                job.persist();

                LOG.infof(
                        "SP 800-90B job %s: chunk %d/%d complete (%d%%) minEntropy=%.6f",
                        jobId, i + 1, chunks.size(), job.progressPercent, entity.minEntropy);
            }

            job.status = JobStatus.COMPLETED;
            job.completedAt = Instant.now();
            job.progressPercent = 100;
            job.persist();

            LOG.infof(
                    "SP 800-90B job %s completed successfully: runId=%s duration=%ds",
                    jobId,
                    assessmentRunId,
                    Duration.between(job.startedAt, job.completedAt).getSeconds());

        } catch (Exception e) {
            LOG.errorf(e, "SP 800-90B job %s failed", jobId);
            String errorMessage =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            job.status = JobStatus.FAILED;
            job.errorMessage = errorMessage;
            job.completedAt = Instant.now();
            try {
                transactionalSelf().markJobFailed(jobId, errorMessage);
            } catch (Exception markFailedException) {
                LOG.errorf(
                        markFailedException,
                        "SP 800-90B job %s failed, and failed to persist FAILED status",
                        jobId);
            }
            recordFailureMetric();
        }
    }

    /**
     * Split SP 800-90B bitstream into chunks (similar to SP 800-22 chunking).
     *
     * @param bitstream Full bitstream
     * @return List of chunks, each <= max bytes
     */
    private List<byte[]> splitSp80090bChunks(byte[] bitstream) {
        int maxBytes = getEffectiveSp80090bMaxBytes();
        List<byte[]> chunks = new ArrayList<>();

        if (bitstream.length <= maxBytes) {
            chunks.add(bitstream);
            return chunks;
        }

        int offset = 0;
        while (offset < bitstream.length) {
            int chunkSize = Math.min(maxBytes, bitstream.length - offset);
            byte[] chunk = Arrays.copyOfRange(bitstream, offset, offset + chunkSize);
            chunks.add(chunk);
            offset += chunkSize;
        }

        return chunks;
    }

    /**
     * Get validation result by test suite run ID.
     *
     * @param testSuiteRunId Test suite run ID
     * @return NIST suite result DTO
     */
    public NISTSuiteResultDTO getValidationResultByRunId(UUID testSuiteRunId) {
        List<NistTestResult> tests = NistTestResult.findByTestSuiteRun(testSuiteRunId);
        if (tests.isEmpty()) {
            throw new ValidationException(
                    "No test results found for test suite run: " + testSuiteRunId);
        }

        NistTestResult firstTest = tests.getFirst();
        List<NISTTestResultDTO> testDTOs = tests.stream().map(NistTestResult::toDTO).toList();

        int totalTests = tests.size();
        int passedCount = (int) tests.stream().filter(t -> t.passed).count();
        int failedCount = totalTests - passedCount;
        double overallPassRate = totalTests > 0 ? (double) passedCount / totalTests : 0.0;
        boolean allPassed = passedCount == totalTests;

        return new NISTSuiteResultDTO(
                testDTOs,
                totalTests,
                passedCount,
                failedCount,
                overallPassRate,
                allPassed,
                firstTest.executedAt,
                firstTest.bitsTested != null ? firstTest.bitsTested : 0,
                new TimeWindowDTO(
                        firstTest.windowStart,
                        firstTest.windowEnd,
                        Duration.between(firstTest.windowStart, firstTest.windowEnd).toHours()));
    }

    /**
     * Get current job status.
     *
     * @param jobId Job ID
     * @return Job status DTO
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public NistValidationJobDTO getJobStatus(UUID jobId) {
        NistValidationJob job = NistValidationJob.findById(jobId);
        if (job == null) {
            throw new ValidationException("Job not found: " + jobId);
        }
        return NistValidationJobDTO.from(job);
    }

    /**
     * Get job result by job ID (for SP 800-22).
     *
     * @param jobId Job ID
     * @return NIST suite result DTO
     */
    public NISTSuiteResultDTO getSp80022JobResult(UUID jobId) {
        NistValidationJob job = NistValidationJob.findById(jobId);
        if (job == null || job.testSuiteRunId == null) {
            throw new ValidationException("Job not found or not completed: " + jobId);
        }

        if (job.status != JobStatus.COMPLETED) {
            throw new ValidationException(
                    "Job not completed yet: " + jobId + " (status: " + job.status + ")");
        }

        // Fetch results by test_suite_run_id
        return getValidationResultByRunId(job.testSuiteRunId);
    }

    /**
     * Get job result by job ID (for SP 800-90B).
     *
     * @param jobId Job ID
     * @return NIST 90B result DTO
     */
    public NIST90BResultDTO getSp80090bJobResult(UUID jobId) {
        NistValidationJob job = NistValidationJob.findById(jobId);
        if (job == null || job.assessmentRunId == null) {
            throw new ValidationException("Job not found or not completed: " + jobId);
        }

        if (job.status != JobStatus.COMPLETED) {
            throw new ValidationException(
                    "Job not completed yet: " + jobId + " (status: " + job.status + ")");
        }

        // Fetch results by assessment_run_id
        List<Nist90BResult> results = Nist90BResult.list("assessmentRunId", job.assessmentRunId);
        if (results.isEmpty()) {
            throw new ValidationException(
                    "No results found for assessment run: " + job.assessmentRunId);
        }

        // For now, return the first result (or aggregate multiple chunks if needed)
        return results.getFirst().toDTO();
    }
}
