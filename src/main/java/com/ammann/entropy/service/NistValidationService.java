package com.ammann.entropy.service;

import com.ammann.entropy.dto.NIST90BResultDTO;
import com.ammann.entropy.dto.NISTSuiteResultDTO;
import com.ammann.entropy.dto.NISTTestResultDTO;
import com.ammann.entropy.dto.TimeWindowDTO;
import com.ammann.entropy.exception.NistException;
import com.ammann.entropy.grpc.proto.sp80022.*;
import com.ammann.entropy.grpc.proto.sp80090b.*;
import com.ammann.entropy.model.EntropyData;
import com.ammann.entropy.model.Nist90BResult;
import com.ammann.entropy.model.NistTestResult;
import io.grpc.CallCredentials;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
    private static final int MIN_BITS_REQUIRED = 1_000_000; // 1 Mbit minimum for NIST tests

    @GrpcClient("sp80022-test-service")
    MutinySp80022TestServiceGrpc.MutinySp80022TestServiceStub sp80022Client;
    @GrpcClient("sp80090b-assessment-service")
    MutinySp80090bAssessmentServiceGrpc.MutinySp80090bAssessmentServiceStub sp80090bClient;

    private Sp80022TestService clientOverride;
    private Sp80090bAssessmentService sp80090bOverride;
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
        LOG.infof("Validating time window: %s to %s (token propagation: %b)", start, end, bearerToken != null);

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
        long bitstreamLength = bitstream.length * 8L;

        if (bitstreamLength < MIN_BITS_REQUIRED) {
            LOG.warnf("Insufficient bits: %d (minimum: %d)", bitstreamLength, MIN_BITS_REQUIRED);
            recordFailureMetric();
            throw new NistException(
                    String.format("Need at least %d bits, got %d", MIN_BITS_REQUIRED, bitstreamLength));
        }

        LOG.infof("Extracted %d bits (%d bytes) for NIST testing", bitstreamLength, bitstream.length);
        String batchId = events.getFirst().batchId;

        // Call NIST SP 800-22 gRPC service
        Sp80022TestResponse grpcResult = runSp80022Tests(bitstream, bearerToken);

        // Also run NIST SP 800-90B entropy assessment
        NIST90BResultDTO entropyAssessment = assess90B(bitstream, batchId, start, end, bearerToken);
        LOG.infof("NIST SP 800-90B assessment: min=%.6f, passed=%b",
                entropyAssessment.minEntropy(), entropyAssessment.passed());

        // Persist results
        UUID testSuiteRunId = UUID.randomUUID();
        List<NISTTestResultDTO> testDTOs = new ArrayList<>();

        for (Sp80022TestResult test : grpcResult.getResultsList()) {
            NistTestResult entity = new NistTestResult(
                    testSuiteRunId,
                    test.getName(),
                    test.getPassed(),
                    test.getPValue(),
                    start,
                    end
            );
            entity.dataSampleSize = bitstreamLength;
            entity.bitsTested = bitstreamLength;
            entity.batchId = batchId;
            entity.details = test.hasWarning() ? test.getWarning() : null;

            em.persist(entity);
            testDTOs.add(entity.toDTO());
        }

        LOG.infof("Persisted %d NIST test results with run ID: %s", testDTOs.size(), testSuiteRunId);

        // Calculate passed/failed from actual results
        int passedCount = (int) grpcResult.getResultsList().stream().filter(Sp80022TestResult::getPassed).count();
        int totalTests = grpcResult.getTestsRun();
        int failedCount = totalTests - passedCount;

        // Build result DTO
        return new NISTSuiteResultDTO(
                testDTOs,
                totalTests,
                passedCount,
                failedCount,
                grpcResult.getOverallPassRate(),
                grpcResult.getNistCompliant(), // Use nist_compliant as uniformity indicator
                Instant.now(),
                bitstreamLength,
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
                client = client.withCallCredentials(new BearerTokenCallCredentials(token));
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
                    client = client.withCallCredentials(new BearerTokenCallCredentials(token));
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
                entropyResult.getAssessmentSummary(),
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

        return new NISTSuiteResultDTO(
                testDTOs,
                tests.size(),
                passedCount,
                failedCount,
                passRate,
                true, // Assuming uniformity check passed (not stored separately)
                firstTest.executedAt,
                firstTest.dataSampleSize,
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

    /**
     * gRPC CallCredentials that adds Bearer token to metadata.
     * Sets the "authorization" header with "Bearer TOKEN" format.
     */
    private static class BearerTokenCallCredentials extends CallCredentials
    {
        private static final Metadata.Key<String> AUTHORIZATION_KEY =
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
        private final String token;

        BearerTokenCallCredentials(String token)
        {
            this.token = token;
        }

        @Override
        public void applyRequestMetadata(RequestInfo requestInfo, Executor executor, MetadataApplier applier)
        {
            executor.execute(() -> {
                try {
                    Metadata headers = new Metadata();
                    headers.put(AUTHORIZATION_KEY, "Bearer " + token);
                    applier.apply(headers);
                } catch (Exception e) {
                    applier.fail(Status.UNAUTHENTICATED.withCause(e));
                }
            });
        }
    }
}