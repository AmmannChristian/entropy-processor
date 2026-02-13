package com.ammann.entropy.service;

import com.ammann.entropy.dto.NIST90BResultDTO;
import com.ammann.entropy.dto.NISTSuiteResultDTO;
import com.ammann.entropy.dto.NISTTestResultDTO;
import com.ammann.entropy.dto.TimeWindowDTO;
import com.ammann.entropy.exception.NistException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ammann.entropy.grpc.proto.sp80022.*;
import com.ammann.entropy.grpc.proto.sp80090b.*;
import com.ammann.entropy.model.EntropyData;
import com.ammann.entropy.model.Nist90BResult;
import com.ammann.entropy.model.NistTestResult;
import io.grpc.CallCredentials;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

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
public class NistValidationService
{

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

    private final EntityManager em;
    private final MeterRegistry meterRegistry;
    private final OidcClientService oidcClientService;

    private Counter validationFailureCounter;

    @Inject
    public NistValidationService(EntityManager em,
                                 MeterRegistry meterRegistry,
                                 OidcClientService oidcClientService)
    {
        this.em = em;
        this.meterRegistry = meterRegistry;
        this.oidcClientService = oidcClientService;
    }

    void initMetrics()
    {
        if (meterRegistry != null && validationFailureCounter == null) {
            validationFailureCounter = Counter.builder("nist_validation_failures_total")
                    .description("Count of NIST validation failures")
                    .register(meterRegistry);
        }
    }

    /**
     * Hourly scheduled NIST SP 800-22 validation.
     * <p>
     * Runs at the top of every hour (HH:00:00).
     * Analyzes entropy data from the previous hour.
     */
    @Scheduled(cron = "0 0 * * * ?")
    @Transactional
    public void runHourlyNISTValidation()
    {
        initMetrics();
        LOG.info("Starting hourly NIST SP 800-22 validation");

        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofHours(1));

        try {
            NISTSuiteResultDTO result = validateTimeWindow(start, end);

            if (result.allTestsPassed()) {
                LOG.infof("NIST validation PASSED: %d/%d tests, uniformity=%b",
                        result.passedTests(), result.totalTests(), result.uniformityCheck());
            } else {
                LOG.warnf("NIST validation FAILED: %d/%d tests passed, uniformity=%b",
                        result.passedTests(), result.totalTests(), result.uniformityCheck());
                LOG.warnf("Recommendation: %s", result.getRecommendation());
            }

        } catch (Exception e) {
            LOG.errorf(e, "Hourly NIST validation failed");
            recordFailureMetric();
        }
    }

    /**
     * Weekly scheduled NIST SP 800-90B validation.
     * <p>
     * Default schedule is Sunday at 00:00 UTC and can be overridden with
     * nist.sp80090b.weekly-cron / SP80090B_WEEKLY_CRON.
     */
    @Scheduled(cron = "{nist.sp80090b.weekly-cron}")
    @Transactional
    public void runWeeklyNIST90BValidation()
    {
        initMetrics();
        LOG.info("Starting weekly NIST SP 800-90B validation");

        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofDays(7));

        try {
            NIST90BResultDTO result = validate90BTimeWindow(start, end);
            LOG.infof("Weekly NIST SP 800-90B completed: minEntropy=%.6f, passed=%b, bits=%d",
                    result.minEntropy(), result.passed(), result.bitsTested());
        } catch (Exception e) {
            LOG.errorf(e, "Weekly NIST SP 800-90B validation failed");
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
    public NISTSuiteResultDTO validateTimeWindow(Instant start, Instant end)
    {
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
    public NISTSuiteResultDTO validateTimeWindow(Instant start, Instant end, String bearerToken)
    {
        initMetrics();
        LOG.infof("Validating NIST SP 800-22 window: %s to %s (token propagation: %b)", start, end, bearerToken != null);

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
            LOG.warnf("Insufficient bits for NIST SP 800-22: %d (minimum: %d)", bitstreamLengthBits, minBitsRequired);
            recordFailureMetric();
            throw new NistException(
                    String.format("Need at least %d bits, got %d", minBitsRequired, bitstreamLengthBits));
        }

        validateSp80022ChunkConfig();
        List<byte[]> chunks = splitSp80022Chunks(bitstream);
        LOG.infof("NIST SP 800-22 run window=%s..%s totalBytes=%d totalBits=%d chunks=%d maxChunkBytes=%d",
                start, end, bitstream.length, bitstreamLengthBits, chunks.size(), getEffectiveSp80022MaxBytes());

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
            int chunkPassedCount = (int) grpcResult.getResultsList().stream().filter(Sp80022TestResult::getPassed).count();
            int chunkTotalTests = grpcResult.getTestsRun();

            LOG.infof("NIST SP 800-22 chunk=%d/%d bytes=%d bits=%d passed=%d/%d compliant=%b passRate=%.6f",
                    i + 1,
                    chunks.size(),
                    chunk.length,
                    chunkBits,
                    chunkPassedCount,
                    chunkTotalTests,
                    grpcResult.getNistCompliant(),
                    grpcResult.getOverallPassRate());

            collectSp80022ChunkResults(grpcResult,
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

        LOG.infof("NIST SP 800-22 run completed: runId=%s passed=%d/%d passRate=%.6f allChunksCompliant=%b",
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
                new TimeWindowDTO(start, end, Duration.between(start, end).toHours())
        );
    }

    /**
     * Runs NIST SP 800-22 tests via gRPC.
     *
     * @param bitstream   The bitstream to test
     * @param bearerToken Optional bearer token for authentication (null = use OidcClientService)
     * @return The gRPC response
     */
    private Sp80022TestResponse runSp80022Tests(byte[] bitstream, String bearerToken)
    {
        Sp80022TestRequest request = Sp80022TestRequest.newBuilder()
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

    private void collectSp80022ChunkResults(Sp80022TestResponse grpcResult,
                                            UUID testSuiteRunId,
                                            String batchId,
                                            Instant start,
                                            Instant end,
                                            int chunkIndex,
                                            int chunkCount,
                                            long chunkBits,
                                            List<NistTestResult> entitiesToPersist,
                                            List<NISTTestResultDTO> testDTOs)
    {
        for (Sp80022TestResult test : grpcResult.getResultsList()) {
            NistTestResult entity = new NistTestResult(
                    testSuiteRunId,
                    test.getName(),
                    test.getPassed(),
                    test.getPValue(),
                    start,
                    end
            );
            entity.dataSampleSize = chunkBits;
            entity.bitsTested = chunkBits;
            entity.batchId = batchId;
            entity.chunkIndex = chunkIndex;
            entity.chunkCount = chunkCount;
            entity.details = test.hasWarning() ? ensureJsonDocument(test.getWarning(), "warning") : null;

            entitiesToPersist.add(entity);
            testDTOs.add(entity.toDTO());
        }
    }

    private void persistNistTestResultsBatch(List<NistTestResult> entitiesToPersist)
    {
        for (NistTestResult entity : entitiesToPersist) {
            em.persist(entity);
        }
        em.flush();
        LOG.infof("Persisted %d NIST SP 800-22 test result rows", entitiesToPersist.size());
    }

    private void validateSp80022ChunkConfig()
    {
        long minBits = getEffectiveSp80022MinBits();
        int maxBytes = getEffectiveSp80022MaxBytes();
        long maxBits = maxBytes * 8L;
        if (maxBits < minBits) {
            throw new NistException(String.format(
                    "Invalid SP800-22 configuration: max bytes (%d) are below min bits (%d)",
                    maxBytes,
                    minBits));
        }
    }

    private List<byte[]> splitSp80022Chunks(byte[] bitstream)
    {
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

    private int getEffectiveSp80022MaxBytes()
    {
        return sp80022MaxBytes > 0 ? sp80022MaxBytes : DEFAULT_SP80022_MAX_BYTES;
    }

    private long getEffectiveSp80022MinBits()
    {
        return sp80022MinBits > 0 ? sp80022MinBits : DEFAULT_SP80022_MIN_BITS;
    }

    private int getEffectiveSp80090bMaxBytes()
    {
        return sp80090bMaxBytes > 0 ? sp80090bMaxBytes : DEFAULT_SP80090B_MAX_BYTES;
    }

    /**
     * Resolves the token to use for gRPC calls.
     *
     * @param bearerToken Propagated token from request (may be null)
     * @param serviceName Name of the service for logging
     * @return Token to use, or null if no authentication required
     */
    private String resolveToken(String bearerToken, String serviceName)
    {
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
                LOG.errorf("Failed to obtain access token for %s call: %s", serviceName, e.getMessage());
                throw new NistException("Authentication required but token unavailable", e);
            }
        }

        LOG.debugf("No authentication configured for %s call", serviceName);
        return null;
    }

    @Transactional
    public NIST90BResultDTO validate90BTimeWindow(Instant start, Instant end)
    {
        return validate90BTimeWindow(start, end, null);
    }

    @Transactional
    public NIST90BResultDTO validate90BTimeWindow(Instant start, Instant end, String bearerToken)
    {
        initMetrics();
        LOG.infof("Validating NIST SP 800-90B window: %s to %s (token propagation: %b)", start, end, bearerToken != null);

        List<EntropyData> events = EntropyData.findInTimeWindow(start, end);
        if (events.isEmpty()) {
            LOG.warnf("Skipping NIST SP 800-90B: no entropy data found in window %s to %s", start, end);
            recordFailureMetric();
            throw new NistException("No entropy data in specified window");
        }

        byte[] bitstream = extractWhitenedBits(events);
        if (bitstream.length == 0) {
            LOG.warnf("Skipping NIST SP 800-90B: extracted bitstream is empty in window %s to %s", start, end);
            recordFailureMetric();
            throw new NistException("No usable entropy bitstream in specified window");
        }

        int sourceBytes = bitstream.length;
        int maxBytes = getEffectiveSp80090bMaxBytes();
        byte[] assessmentSample = bitstream;
        if (sourceBytes > maxBytes) {
            assessmentSample = Arrays.copyOf(bitstream, maxBytes);
            LOG.warnf("NIST SP 800-90B input exceeds limit: sourceBytes=%d maxBytes=%d. Truncating assessment sample.",
                    sourceBytes, maxBytes);
        }

        LOG.infof("NIST SP 800-90B run window=%s..%s sourceBytes=%d assessmentBytes=%d",
                start, end, sourceBytes, assessmentSample.length);

        String batchId = events.getFirst().batchId;
        NIST90BResultDTO result = assess90B(assessmentSample, batchId, start, end, bearerToken);
        LOG.infof("NIST SP 800-90B assessment complete: minEntropy=%.6f, passed=%b, bits=%d",
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
     */
    private NIST90BResultDTO assess90B(byte[] bitstream, String batchId,
                                       Instant start, Instant end, String bearerToken)
    {
        if (sp80090bOverride == null && sp80090bClient == null) {
            LOG.warn("NIST SP 800-90B client not configured");
            recordFailureMetric();
            throw new NistException("NIST SP 800-90B client not available");
        }

        Sp80090bAssessmentRequest request = Sp80090bAssessmentRequest.newBuilder()
                .setData(com.google.protobuf.ByteString.copyFrom(bitstream))
                .setBitsPerSymbol(8) // Byte-level analysis
                .setIidMode(true)    // Run IID tests
                .setNonIidMode(true) // Run Non-IID estimators
                .setVerbosity(1)     // Normal verbosity
                .build();

        Sp80090bAssessmentResponse entropyResult;
        try {
            if (sp80090bOverride != null) {
                entropyResult = sp80090bOverride.assessEntropy(request).await().atMost(Duration.ofMinutes(10));
            } else {
                var client = sp80090bClient;
                String token = resolveToken(bearerToken, "NIST SP 800-90B");
                if (token != null) {
                    client = withBearerToken(client, token);
                }
                entropyResult = client.assessEntropy(request).await().atMost(Duration.ofMinutes(10));
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

        // Extract entropy estimates from EstimatorResults
        double minEntropy = entropyResult.getMinEntropy();
        double shannonEntropy = extractEntropyEstimate(entropyResult, "Shannon");
        double collisionEntropy = extractEntropyEstimate(entropyResult, "Collision");
        double markovEntropy = extractEntropyEstimate(entropyResult, "Markov");
        double compressionEntropy = extractEntropyEstimate(entropyResult, "Compression");

        long bitstreamLength = bitstream.length * 8L;

        Nist90BResult entity = new Nist90BResult(
                batchId,
                minEntropy,
                shannonEntropy,
                collisionEntropy,
                markovEntropy,
                compressionEntropy,
                entropyResult.getPassed(),
                ensureJsonDocument(entropyResult.getAssessmentSummary(), "summary"),
                bitstreamLength,
                start,
                end
        );

        em.persist(entity);
        return entity.toDTO();
    }

    /**
     * Extract entropy estimate from EstimatorResult lists by name pattern.
     */
    private double extractEntropyEstimate(Sp80090bAssessmentResponse response, String namePattern)
    {
        // Search in Non-IID results first (more conservative estimates)
        for (Sp80090bEstimatorResult result : response.getNonIidResultsList()) {
            if (result.getName().toLowerCase().contains(namePattern.toLowerCase())) {
                return result.getEntropyEstimate();
            }
        }
        // Fallback to IID results
        for (Sp80090bEstimatorResult result : response.getIidResultsList()) {
            if (result.getName().toLowerCase().contains(namePattern.toLowerCase())) {
                return result.getEntropyEstimate();
            }
        }
        return 0.0; // Not found
    }

    private String ensureJsonDocument(String rawValue, String fallbackField)
    {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }

        try {
            JSON_MAPPER.readTree(rawValue);
            return rawValue;
        } catch (Exception ignored) {
            return JSON_MAPPER.createObjectNode()
                    .put(fallbackField, rawValue)
                    .toString();
        }
    }

    /**
     * Attaches a Bearer token header to a gRPC stub.
     */
    private <T extends AbstractStub<T>> T withBearerToken(T client, String token)
    {
        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION_KEY, "Bearer " + token);
        return client.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    /**
     * Extracts whitened entropy bits from EntropyData events.
     * <p>
     * Prefer stored whitened_entropy bytes; falls back to interval-based whitening
     * when no pre-whitened data exists.
     *
     * @param events List of EntropyData events
     * @return Byte array containing whitened random bits
     */
    private byte[] extractWhitenedBits(List<EntropyData> events)
    {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(events.size() * 8);
        boolean hasWhitened = false;

        for (EntropyData event : events) {
            if (event.whitenedEntropy != null && event.whitenedEntropy.length > 0) {
                buffer.writeBytes(event.whitenedEntropy);
                hasWhitened = true;
            }
        }

        if (hasWhitened) {
            byte[] bytes = buffer.toByteArray();
            LOG.debugf("Using %d whitened entropy chunks from database", events.size());
            return bytes;
        }

        // Fallback: derive bits from inter-event intervals
        List<EntropyData> sortedEvents = events.stream()
                .sorted((a, b) -> Long.compare(a.hwTimestampNs, b.hwTimestampNs))
                .toList();

        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < sortedEvents.size(); i++) {
            long interval = sortedEvents.get(i).hwTimestampNs - sortedEvents.get(i - 1).hwTimestampNs;
            if (interval > 0) {
                intervals.add(interval);
            }
        }

        ByteBuffer intervalBuffer = ByteBuffer.allocate(intervals.size() * Long.BYTES);
        intervals.forEach(intervalBuffer::putLong);
        byte[] rawBytes = intervalBuffer.array();

        if (rawBytes.length < 2) {
            return rawBytes;
        }

        byte[] whitened = new byte[rawBytes.length / 2];
        for (int i = 0; i < whitened.length; i++) {
            whitened[i] = (byte) (rawBytes[i] ^ rawBytes[i + whitened.length]);
        }

        LOG.debugf("Whitened %d intervals into %d bytes (fallback)", intervals.size(), whitened.length);
        return whitened;
    }

    /**
     * Gets the most recent NIST validation result.
     *
     * @return Most recent suite result or null if no results exist
     */
    public NISTSuiteResultDTO getLatestValidationResult()
    {
        UUID latestRunId = NistTestResult.findMostRecentSuiteRunId();
        if (latestRunId == null) {
            return null;
        }

        List<NistTestResult> tests = NistTestResult.findByTestSuiteRun(latestRunId);
        if (tests.isEmpty()) {
            return null;
        }

        NistTestResult firstTest = tests.getFirst();
        List<NISTTestResultDTO> testDTOs = tests.stream()
                .map(NistTestResult::toDTO)
                .toList();

        int passedCount = (int) tests.stream().filter(t -> t.passed).count();
        int failedCount = tests.size() - passedCount;
        double passRate = (double) passedCount / tests.size();
        long datasetSizeBits = 0L;
        if (firstTest.chunkCount != null && firstTest.chunkCount > 1) {
            boolean[] seenChunks = new boolean[firstTest.chunkCount + 1];
            for (NistTestResult test : tests) {
                if (test.chunkIndex == null || test.chunkIndex <= 0 || test.chunkIndex >= seenChunks.length) {
                    continue;
                }
                if (!seenChunks[test.chunkIndex]) {
                    datasetSizeBits += test.bitsTested != null ? test.bitsTested : 0L;
                    seenChunks[test.chunkIndex] = true;
                }
            }
        }
        if (datasetSizeBits == 0L) {
            datasetSizeBits = firstTest.dataSampleSize != null ? firstTest.dataSampleSize : 0L;
        }

        return new NISTSuiteResultDTO(
                testDTOs,
                tests.size(),
                passedCount,
                failedCount,
                passRate,
                true, // Assuming uniformity check passed (not stored separately)
                firstTest.executedAt,
                datasetSizeBits,
                new TimeWindowDTO(firstTest.windowStart, firstTest.windowEnd,
                        Duration.between(firstTest.windowStart, firstTest.windowEnd).toHours())
        );
    }

    /**
     * Visible for testing only to avoid real gRPC calls.
     */
    void setClientOverride(Sp80022TestService override)
    {
        this.clientOverride = override;
    }

    /**
     * Visible for testing only to avoid real gRPC calls.
     */
    void setSp80090bOverride(Sp80090bAssessmentService override)
    {
        this.sp80090bOverride = override;
    }

    void setSp80022MaxBytesForTesting(int maxBytes)
    {
        this.sp80022MaxBytes = maxBytes;
    }

    void setSp80022MinBitsForTesting(long minBits)
    {
        this.sp80022MinBits = minBits;
    }

    void setSp80090bMaxBytesForTesting(int maxBytes)
    {
        this.sp80090bMaxBytes = maxBytes;
    }

    private static final class BearerTokenCallCredentials extends CallCredentials
    {
        private final String token;

        private BearerTokenCallCredentials(String token)
        {
            this.token = token;
        }

        @Override
        public void applyRequestMetadata(RequestInfo requestInfo, Executor appExecutor, MetadataApplier applier)
        {
            appExecutor.execute(() -> {
                try {
                    Metadata headers = new Metadata();
                    headers.put(AUTHORIZATION_KEY, "Bearer " + token);
                    applier.apply(headers);
                } catch (RuntimeException e) {
                    applier.fail(Status.UNAUTHENTICATED.withCause(e));
                }
            });
        }

        @Override
        public void thisUsesUnstableApi()
        {
            // Required by gRPC CallCredentials contract.
        }
    }

    private void recordFailureMetric()
    {
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
    public Long countRecentFailures(int hours)
    {
        Instant since = Instant.now().minus(Duration.ofHours(hours));
        return PanacheEntityBase.count("executedAt > ?1 AND passed = false", since);
    }

}
