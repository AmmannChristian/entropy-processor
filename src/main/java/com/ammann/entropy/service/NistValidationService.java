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
    private static final int DEFAULT_SP80022_MAX_BYTES = 12_500_000;
    private static final long DEFAULT_SP80022_MIN_BITS = 1_000_000L;
    private static final int DEFAULT_SP80090B_MAX_BYTES = 1_000_000;
    private static final Duration NIST_GRPC_TIMEOUT = Duration.ofMinutes(45);
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

    @ConfigProperty(name = "nist.sp80090b.sample-interval-seconds", defaultValue = "3600")
    int sp80090bSampleIntervalSeconds;

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

        // Extract whitened entropy bits
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

        int maxBytes = getEffectiveSp80022MaxBytes();

        // Single-sequence mode: if data fits within MaxBits, submit as one call
        if (bitstream.length <= maxBytes) {
            return validateSp80022SingleSequence(
                    bitstream,
                    bitstreamLengthBits,
                    start,
                    end,
                    events.getFirst().batchId,
                    bearerToken);
        }

        // Multi-sequence mode: proper NIST SP 800-22 §4.2.1 analysis
        return validateSp80022MultiSequence(
                bitstream, bitstreamLengthBits, start, end, events.getFirst().batchId, bearerToken);
    }

    /**
     * Single-sequence SP 800-22 validation: submits the full bitstream as one continuous sequence.
     * This is the semantically correct mode for typical hourly windows.
     */
    private NISTSuiteResultDTO validateSp80022SingleSequence(
            byte[] bitstream,
            long bitstreamLengthBits,
            Instant start,
            Instant end,
            String batchId,
            String bearerToken) {

        LOG.infof(
                "NIST SP 800-22 single-sequence mode: window=%s..%s bytes=%d bits=%d",
                start, end, bitstream.length, bitstreamLengthBits);

        UUID testSuiteRunId = UUID.randomUUID();
        Sp80022TestResponse grpcResult = runSp80022Tests(bitstream, bearerToken);

        int passedCount =
                (int)
                        grpcResult.getResultsList().stream()
                                .filter(Sp80022TestResult::getPassed)
                                .count();
        int totalTests = grpcResult.getTestsRun();
        int failedCount = totalTests - passedCount;
        double overallPassRate = totalTests > 0 ? (double) passedCount / totalTests : 0.0;

        List<NISTTestResultDTO> testDTOs = new ArrayList<>();
        List<NistTestResult> entitiesToPersist = new ArrayList<>();

        for (Sp80022TestResult test : grpcResult.getResultsList()) {
            NistTestResult entity =
                    new NistTestResult(
                            testSuiteRunId,
                            test.getName(),
                            test.getPassed(),
                            test.getPValue(),
                            start,
                            end);
            entity.dataSampleSize = bitstreamLengthBits;
            entity.bitsTested = bitstreamLengthBits;
            entity.batchId = batchId;
            entity.chunkIndex = 1;
            entity.chunkCount = 1;
            entity.aggregationMethod = "SINGLE_SEQUENCE";
            entity.details =
                    test.hasWarning() ? ensureJsonDocument(test.getWarning(), "warning") : null;
            entitiesToPersist.add(entity);
            testDTOs.add(entity.toDTO());
        }

        persistNistTestResultsBatch(entitiesToPersist);

        LOG.infof(
                "NIST SP 800-22 single-sequence completed: runId=%s passed=%d/%d passRate=%.6f",
                testSuiteRunId, passedCount, totalTests, overallPassRate);

        return new NISTSuiteResultDTO(
                testDTOs,
                totalTests,
                passedCount,
                failedCount,
                overallPassRate,
                failedCount == 0,
                Instant.now(),
                bitstreamLengthBits,
                new TimeWindowDTO(start, end, Duration.between(start, end).toHours()),
                "SINGLE_SEQUENCE");
    }

    /**
     * Multi-sequence SP 800-22 validation per NIST SP 800-22 §4.2.1.
     *
     * <p>Splits the bitstream into N equal-length sequences (N >= 55), runs each independently,
     * then aggregates p-values per test using chi-square uniformity test (10-bin, 9-df).
     * Refuses validation if the bitstream cannot produce >= 55 sequences of >= MinBits each.
     */
    private NISTSuiteResultDTO validateSp80022MultiSequence(
            byte[] bitstream,
            long bitstreamLengthBits,
            Instant start,
            Instant end,
            String batchId,
            String bearerToken) {

        int maxBytes = getEffectiveSp80022MaxBytes();
        int minBytesPerSequence = (int) Math.ceil(getEffectiveSp80022MinBits() / 8.0);
        int minSequences = 55;

        // Compute sequence count: N equal-length sequences, each <= maxBytes and >= minBytes
        int maxSequencesBySize = bitstream.length / minBytesPerSequence;
        if (maxSequencesBySize < minSequences) {
            throw new NistException(
                    String.format(
                            "Multi-sequence mode requires >= %d sequences of >= %d bytes each."
                                + " Bitstream (%d bytes) can produce at most %d sequences. Reduce"
                                + " the window size to fit within single-sequence limit (%d"
                                + " bytes).",
                            minSequences,
                            minBytesPerSequence,
                            bitstream.length,
                            maxSequencesBySize,
                            maxBytes));
        }

        // Choose N such that each sequence fits within maxBytes
        int sequenceCount = Math.max(minSequences, (bitstream.length + maxBytes - 1) / maxBytes);
        int sequenceLength = bitstream.length / sequenceCount;

        // Verify each sequence meets minimum
        if (sequenceLength < minBytesPerSequence) {
            throw new NistException(
                    String.format(
                            "Multi-sequence mode: %d sequences of %d bytes each is below minimum %d"
                                    + " bytes.",
                            sequenceCount, sequenceLength, minBytesPerSequence));
        }

        LOG.infof(
                "NIST SP 800-22 multi-sequence mode: window=%s..%s bytes=%d sequences=%d"
                        + " seqLength=%d",
                start, end, bitstream.length, sequenceCount, sequenceLength);

        UUID testSuiteRunId = UUID.randomUUID();
        // Collect p-values and pass counts per test name across all sequences
        Map<String, List<Double>> pValuesByTest = new LinkedHashMap<>();
        Map<String, Integer> passCountByTest = new LinkedHashMap<>();
        List<NistTestResult> entitiesToPersist = new ArrayList<>();

        // Truncate to exact multiple of sequenceLength so all sequences are equal length (§4.2.1)
        int usableLength = sequenceCount * sequenceLength;
        byte[] trimmedBitstream = Arrays.copyOf(bitstream, usableLength);

        for (int i = 0; i < sequenceCount; i++) {
            int seqStart = i * sequenceLength;
            int seqEnd = seqStart + sequenceLength;
            byte[] sequence = Arrays.copyOfRange(trimmedBitstream, seqStart, seqEnd);

            Sp80022TestResponse grpcResult = runSp80022Tests(sequence, bearerToken);

            long seqBits = sequence.length * 8L;
            for (Sp80022TestResult test : grpcResult.getResultsList()) {
                pValuesByTest
                        .computeIfAbsent(test.getName(), k -> new ArrayList<>())
                        .add(test.getPValue());
                if (test.getPassed()) {
                    passCountByTest.merge(test.getName(), 1, Integer::sum);
                } else {
                    passCountByTest.putIfAbsent(test.getName(), 0);
                }

                NistTestResult entity =
                        new NistTestResult(
                                testSuiteRunId,
                                test.getName(),
                                test.getPassed(),
                                test.getPValue(),
                                start,
                                end);
                entity.dataSampleSize = seqBits;
                entity.bitsTested = seqBits;
                entity.batchId = batchId;
                entity.chunkIndex = i + 1;
                entity.chunkCount = sequenceCount;
                entity.aggregationMethod = "MULTI_SEQUENCE_CHI2";
                entity.details =
                        test.hasWarning() ? ensureJsonDocument(test.getWarning(), "warning") : null;
                entitiesToPersist.add(entity);
            }

            LOG.infof(
                    "NIST SP 800-22 multi-sequence: sequence %d/%d complete (%d bytes)",
                    i + 1, sequenceCount, sequence.length);
        }

        persistNistTestResultsBatch(entitiesToPersist);

        // NIST SP 800-22 §4.2.1 two-check aggregation per test:
        // 1. Chi-square uniformity on p-value distribution
        // 2. Pass-proportion: proportion of sequences passing must exceed threshold
        double alpha = 0.01;
        double proportionThreshold =
                1.0 - alpha - 3.0 * Math.sqrt(alpha * (1.0 - alpha) / sequenceCount);

        List<NISTTestResultDTO> aggregatedDTOs = new ArrayList<>();
        int passedCount = 0;

        for (Map.Entry<String, List<Double>> entry : pValuesByTest.entrySet()) {
            String testName = entry.getKey();
            List<Double> pValues = entry.getValue();

            double chi2PValue = computeChiSquareUniformity(pValues);
            boolean chi2Passed = chi2PValue >= 0.0001; // NIST threshold for uniformity

            int passes = passCountByTest.getOrDefault(testName, 0);
            double proportion = (double) passes / sequenceCount;
            boolean proportionPassed = proportion >= proportionThreshold;

            boolean testPassed = chi2Passed && proportionPassed;
            if (testPassed) passedCount++;

            String details = null;
            if (!proportionPassed) {
                details =
                        ensureJsonDocument(
                                String.format(
                                        "passRatio=%.4f threshold=%.4f chi2PValue=%.6f",
                                        proportion, proportionThreshold, chi2PValue),
                                "proportion_fail");
            }

            aggregatedDTOs.add(
                    new NISTTestResultDTO(
                            testName,
                            testPassed,
                            chi2PValue,
                            testPassed ? "PASS" : "FAIL",
                            Instant.now(),
                            details,
                            "MULTI_SEQUENCE_CHI2",
                            null,
                            null,
                            null));
        }

        int totalTests = aggregatedDTOs.size();
        int failedCount = totalTests - passedCount;
        double overallPassRate = totalTests > 0 ? (double) passedCount / totalTests : 0.0;

        LOG.infof(
                "NIST SP 800-22 multi-sequence completed: runId=%s sequences=%d"
                        + " passed=%d/%d passRate=%.6f",
                testSuiteRunId, sequenceCount, passedCount, totalTests, overallPassRate);

        return new NISTSuiteResultDTO(
                aggregatedDTOs,
                totalTests,
                passedCount,
                failedCount,
                overallPassRate,
                failedCount == 0,
                Instant.now(),
                bitstreamLengthBits,
                new TimeWindowDTO(start, end, Duration.between(start, end).toHours()),
                "MULTI_SEQUENCE_CHI2");
    }

    /**
     * Computes chi-square uniformity p-value for a list of p-values per NIST SP 800-22 §4.2.1.
     * Bins p-values into 10 equal-width intervals [0,0.1)...[0.9,1.0) and computes chi-square
     * statistic with 9 degrees of freedom.
     */
    private double computeChiSquareUniformity(List<Double> pValues) {
        if (pValues.isEmpty()) return 0.0;

        int numBins = 10;
        int[] bins = new int[numBins];
        int validCount = 0;

        for (double pval : pValues) {
            if (pval < 0.0 || pval > 1.0) continue;
            int binIndex = Math.min((int) (pval * numBins), numBins - 1);
            bins[binIndex]++;
            validCount++;
        }

        if (validCount == 0) return 0.0;

        double expected = (double) validCount / numBins;
        double chi2 = 0.0;
        for (int observed : bins) {
            double diff = observed - expected;
            chi2 += (diff * diff) / expected;
        }

        // Survival function of chi-squared distribution with df=9
        // Using igamc (regularized upper incomplete gamma function)
        double df = numBins - 1.0;
        return regularizedGammaQ(df / 2.0, chi2 / 2.0);
    }

    /**
     * Regularized upper incomplete gamma function Q(a, x) = 1 - P(a, x).
     * Used for computing chi-square p-values. Uses series expansion for small x
     * and continued fraction for large x.
     */
    private double regularizedGammaQ(double a, double x) {
        if (x < 0.0 || a <= 0.0) return 0.0;
        if (x == 0.0) return 1.0;

        // Use series for x < a+1, continued fraction otherwise
        if (x < a + 1.0) {
            return 1.0 - regularizedGammaP(a, x);
        }
        return regularizedGammaCF(a, x);
    }

    /**
     * Regularized lower incomplete gamma P(a, x) via series expansion.
     */
    private double regularizedGammaP(double a, double x) {
        double lnGammaA = lnGamma(a);
        double ap = a;
        double sum = 1.0 / a;
        double del = sum;
        for (int n = 1; n <= 200; n++) {
            ap += 1.0;
            del *= x / ap;
            sum += del;
            if (Math.abs(del) < Math.abs(sum) * 1e-14) break;
        }
        return sum * Math.exp(-x + a * Math.log(x) - lnGammaA);
    }

    /**
     * Regularized upper incomplete gamma Q(a, x) via continued fraction.
     */
    private double regularizedGammaCF(double a, double x) {
        double lnGammaA = lnGamma(a);
        double b = x + 1.0 - a;
        double c = 1.0 / 1e-30;
        double d = 1.0 / b;
        double h = d;
        for (int i = 1; i <= 200; i++) {
            double an = -i * (i - a);
            b += 2.0;
            d = an * d + b;
            if (Math.abs(d) < 1e-30) d = 1e-30;
            c = b + an / c;
            if (Math.abs(c) < 1e-30) c = 1e-30;
            d = 1.0 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1.0) < 1e-14) break;
        }
        return Math.exp(-x + a * Math.log(x) - lnGammaA) * h;
    }

    /**
     * Natural log of the gamma function (Lanczos approximation).
     */
    private double lnGamma(double x) {
        double[] cof = {
            76.18009172947146, -86.50532032941677, 24.01409824083091,
            -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5
        };
        double y = x;
        double tmp = x + 5.5;
        tmp -= (x + 0.5) * Math.log(tmp);
        double ser = 1.000000000190015;
        for (double co : cof) {
            y += 1.0;
            ser += co / y;
        }
        return -tmp + Math.log(2.5066282746310005 * ser / x);
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
                return clientOverride.runTestSuite(request).await().atMost(NIST_GRPC_TIMEOUT);
            }

            var client = sp80022Client;
            String token = resolveToken(bearerToken, "NIST SP 800-22");
            if (token != null) {
                client = withBearerToken(client, token);
            }
            return client.runTestSuite(request).await().atMost(NIST_GRPC_TIMEOUT);

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

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void persistNistTestResultsBatch(List<NistTestResult> entitiesToPersist) {
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

        int maxBytes = getEffectiveSp80090bMaxBytes();
        long windowDurationSeconds = computeWindowDurationSeconds(events, start, end);
        List<int[]> samplePositions =
                compute90BSamplePositions(bitstream.length, maxBytes, windowDurationSeconds);
        int sampleCount = samplePositions.size();

        LOG.infof(
                "NIST SP 800-90B run window=%s..%s sourceBytes=%d sampleCount=%d sampleSize=%d"
                        + " windowDuration=%ds",
                start, end, bitstream.length, sampleCount, maxBytes, windowDurationSeconds);

        String batchId = events.getFirst().batchId;
        UUID assessmentRunId = UUID.randomUUID();
        int bytesPerEvent = GrpcMappingService.EXPECTED_WHITENED_ENTROPY_BYTES;

        double worstMinEntropy = Double.MAX_VALUE;
        Sp80090bAssessmentResponse worstResponse = null;
        int worstSampleIndex = 0;
        boolean allPassed = true;
        long totalBits = 0L;

        for (int i = 0; i < sampleCount; i++) {
            int[] pos = samplePositions.get(i);
            int sampleStart = pos[0];
            int sampleEnd = pos[1];
            byte[] sample = Arrays.copyOfRange(bitstream, sampleStart, sampleEnd);

            // Resolve hwTimestampNs for first and last events contributing to this sample
            int firstEventIndex = sampleStart / bytesPerEvent;
            int lastEventIndex = Math.max(firstEventIndex, (sampleEnd - 1) / bytesPerEvent);
            Instant firstEventTs = resolveEventTimestamp(events, firstEventIndex);
            Instant lastEventTs = resolveEventTimestamp(events, lastEventIndex);

            Sp80090bOutcome outcome =
                    assess90B(sample, batchId, start, end, bearerToken, assessmentRunId);

            Nist90BResult entity = outcome.entity();
            entity.sampleIndex = i + 1;
            entity.sampleCount = sampleCount;
            entity.sampleByteOffsetStart = (long) sampleStart;
            entity.sampleByteOffsetEnd = (long) sampleEnd;
            entity.sampleFirstEventTimestamp = firstEventTs;
            entity.sampleLastEventTimestamp = lastEventTs;
            entity.assessmentScope = "NIST_SINGLE_SAMPLE";
            long actualSampleBytes = sampleEnd - sampleStart;
            entity.sampleSizeMeetsNistMinimum = actualSampleBytes >= getEffectiveSp80090bMaxBytes();
            em.persist(entity);

            if (outcome.response().getMinEntropy() < worstMinEntropy) {
                worstMinEntropy = outcome.response().getMinEntropy();
                worstResponse = outcome.response();
                worstSampleIndex = i;
            }

            allPassed &= outcome.response().getPassed();
            totalBits += entity.bitsTested != null ? entity.bitsTested : 0L;

            LOG.infof(
                    "NIST SP 800-90B sample %d/%d complete: minEntropy=%.6f passed=%b"
                            + " byteRange=[%d,%d)",
                    i + 1, sampleCount, entity.minEntropy, entity.passed, sampleStart, sampleEnd);
        }

        // Write estimators from worst sample
        writeEstimatorsForRun(assessmentRunId, worstResponse, worstSampleIndex);

        // Create run-summary row (product-defined aggregation)
        Nist90BResult summary =
                new Nist90BResult(batchId, worstMinEntropy, allPassed, null, totalBits, start, end);
        summary.assessmentRunId = assessmentRunId;
        summary.isRunSummary = true;
        summary.sampleCount = sampleCount;
        summary.assessmentScope = "PRODUCT_WINDOW_SUMMARY";
        em.persist(summary);

        NIST90BResultDTO result = summary.toDTO();
        LOG.infof(
                "NIST SP 800-90B assessment complete: minEntropy=%.6f, passed=%b, samples=%d,"
                        + " totalBits=%d",
                result.minEntropy(), result.passed(), sampleCount, totalBits);
        return result;
    }

    /**
     * Computes evenly-spaced sample positions across the bitstream for SP 800-90B multi-point
     * sampling. Each sample is exactly {@code sampleSize} bytes. Sample count is derived from
     * the actual observation window duration and configured sample interval, not from data size.
     *
     * @param totalBytes            total bitstream length
     * @param sampleSize            bytes per sample (typically 1,000,000)
     * @param windowDurationSeconds actual observation span in seconds (from first to last event)
     * @return list of [startOffset, endOffset) pairs
     */
    List<int[]> compute90BSamplePositions(
            int totalBytes, int sampleSize, long windowDurationSeconds) {
        if (totalBytes <= sampleSize) {
            return List.of(new int[] {0, totalBytes});
        }

        // Time-based sample count: one sample per configured interval
        int timeBased =
                Math.max(
                        1,
                        (int)
                                Math.floor(
                                        (double) windowDurationSeconds
                                                / sp80090bSampleIntervalSeconds));
        // Cannot have more samples than data allows
        int maxSamples = totalBytes / sampleSize;
        int sampleCount = Math.min(timeBased, maxSamples);

        if (sampleCount <= 1) {
            return List.of(new int[] {0, Math.min(totalBytes, sampleSize)});
        }

        // Evenly-spaced: distribute sampleCount samples across totalBytes
        List<int[]> positions = new ArrayList<>();
        double step = (double) (totalBytes - sampleSize) / (sampleCount - 1);
        for (int i = 0; i < sampleCount; i++) {
            int startOffset = (int) Math.round(i * step);
            startOffset = Math.min(startOffset, totalBytes - sampleSize);
            positions.add(new int[] {startOffset, startOffset + sampleSize});
        }
        return positions;
    }

    /**
     * Resolves the hwTimestampNs of the event at the given index, converted to an Instant.
     */
    private Instant resolveEventTimestamp(List<EntropyData> events, int eventIndex) {
        if (eventIndex < 0 || eventIndex >= events.size()) {
            return null;
        }
        Long hwNs = events.get(eventIndex).hwTimestampNs;
        if (hwNs == null) {
            return null;
        }
        return Instant.ofEpochSecond(hwNs / 1_000_000_000L, hwNs % 1_000_000_000L);
    }

    /**
     * Computes the actual observation window duration in seconds from event hardware timestamps.
     * Falls back to the requested window bounds if events lack hardware timestamps.
     */
    private long computeWindowDurationSeconds(
            List<EntropyData> events, Instant start, Instant end) {
        if (events.size() >= 2) {
            Long firstHw = events.getFirst().hwTimestampNs;
            Long lastHw = events.getLast().hwTimestampNs;
            if (firstHw != null && lastHw != null && lastHw > firstHw) {
                long hwSeconds = (lastHw - firstHw) / 1_000_000_000L;
                if (hwSeconds > 0) {
                    return hwSeconds;
                }
            }
        }
        return Math.max(0, Duration.between(start, end).toSeconds());
    }

    /**
     * Pairs a persisted {@link Nist90BResult} chunk entity with its raw gRPC response.
     *
     * <p>This record exists because callers need the gRPC response to extract estimator
     * results after the chunk loop, but the entity must be persisted immediately during
     * chunk processing. Returning both avoids a redundant gRPC call or database lookup.
     */
    private record Sp80090bOutcome(Nist90BResult entity, Sp80090bAssessmentResponse response) {}

    /**
     * Runs a NIST SP 800-90B entropy assessment via gRPC for a single chunk, persists the
     * result entity, and returns both the entity and the raw gRPC response.
     *
     * <p>This method does <em>not</em> write estimator rows. Callers must invoke
     * {@link #writeEstimatorsForRun} explicitly after all chunks have been processed
     * and the worst-minEntropy chunk has been identified.
     *
     * @param bitstream       bitstream to assess (one chunk)
     * @param batchId         batch identifier for traceability
     * @param start           start of the entropy data time window
     * @param end             end of the entropy data time window
     * @param bearerToken     optional bearer token (null falls back to OidcClientService)
     * @param assessmentRunId run identifier shared across all chunks of a single job
     * @return paired entity (already persisted and managed) and raw gRPC response
     */
    private Sp80090bOutcome assess90B(
            byte[] bitstream,
            String batchId,
            Instant start,
            Instant end,
            String bearerToken,
            UUID assessmentRunId) {
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
                                .atMost(NIST_GRPC_TIMEOUT);
            } else {
                var client = sp80090bClient;
                String token = resolveToken(bearerToken, "NIST SP 800-90B");
                if (token != null) {
                    client = withBearerToken(client, token);
                }
                entropyResult =
                        client.assessEntropy(request).await().atMost(NIST_GRPC_TIMEOUT);
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

        long bitstreamLength = bitstream.length * 8L;

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

        return new Sp80090bOutcome(entity, entropyResult);
    }

    /**
     * Persists all 14 estimator rows (10 Non-IID + 4 IID) for a completed assessment run.
     *
     * <p>Must be called exactly once per run, after the chunk loop completes. The caller
     * must pass the gRPC response from the chunk with the lowest minEntropy (the worst
     * chunk), whose estimator values become the canonical result for the entire run.
     * The database unique constraint {@code uq_estimator_per_run} prevents duplicate writes.
     *
     * @param assessmentRunId  run identifier shared across all chunks
     * @param response         gRPC response from the worst-minEntropy chunk
     * @param sourceChunkIndex 0-based index of the chunk whose response is used (for traceability)
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void writeEstimatorsForRun(
            UUID assessmentRunId, Sp80090bAssessmentResponse response, int sourceChunkIndex) {
        LOG.debugf(
                "Writing estimators for run %s from source chunk %d",
                assessmentRunId, sourceChunkIndex);
        for (Sp80090bEstimatorResult est : response.getNonIidResultsList()) {
            persistEstimator(assessmentRunId, TestType.NON_IID, est);
        }
        for (Sp80090bEstimatorResult est : response.getIidResultsList()) {
            persistEstimator(assessmentRunId, TestType.IID, est);
        }
    }

    /**
     * Persists a single estimator result to the nist_90b_estimator_results table.
     *
     * <p>Part of V2a dual-write strategy to store ALL 14 estimators (10 Non-IID + 4 IID)
     * with full metadata (passed, details, description).
     *
     * <p><b>Entropy Semantics:</b> Upstream {@code -1.0} sentinel values are mapped to
     * {@code null} to distinguish non-entropy estimators from true zero-entropy results.
     *
     * @param assessmentRunId Assessment run identifier (foreign key)
     * @param testType Test type (IID or NON_IID)
     * @param proto Upstream protobuf estimator result
     */
    private void persistEstimator(
            UUID assessmentRunId, TestType testType, Sp80090bEstimatorResult proto) {

        // Entropy semantics: upstream -1.0 indicates a non-entropy estimator and is mapped to null.
        // True 0.0 indicates a degenerate source and is preserved.
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
        return getValidationResultByRunId(latestRunId);
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

    void setSp80090bSampleIntervalSecondsForTesting(int seconds) {
        this.sp80090bSampleIntervalSeconds = seconds;
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
        // Outer TX provides Hibernate session for reads on async thread.
        // No timeout — all writes use REQUIRES_NEW and are independent.
        QuarkusTransaction.requiringNew()
                .run(() -> processSp80022ValidationJob(jobId, bearerToken));
    }

    private void runSp80090bWorkerWithExtendedTimeout(UUID jobId, String bearerToken) {
        LOG.infof("Starting worker thread for SP 800-90B job %s", jobId);
        QuarkusTransaction.requiringNew()
                .run(() -> processSp80090bValidationJob(jobId, bearerToken));
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
     *
     * <p>Uses single-sequence mode when data fits within MaxBits, or proper NIST SP 800-22 §4.2.1
     * multi-sequence analysis with chi-square aggregation for larger bitstreams.
     *
     * @param jobId       Job ID to process
     * @param bearerToken Bearer token for gRPC calls
     */
    public void processSp80022ValidationJob(UUID jobId, String bearerToken) {
        NistValidationJob job = NistValidationJob.findById(jobId);
        if (job == null) {
            LOG.errorf("SP 800-22 job %s not found — skipping", jobId);
            return;
        }

        transactionalSelf().markJobRunning(jobId);
        LOG.infof("SP 800-22 job %s started", jobId);

        try {

            Instant windowStart = job.windowStart;
            Instant windowEnd = job.windowEnd;

            List<EntropyData> events = EntropyData.findInTimeWindow(windowStart, windowEnd);
            if (events.isEmpty()) {
                throw new NistException("No entropy data in specified window");
            }

            byte[] bitstream = extractWhitenedBits(events);
            long bitstreamLengthBits = bitstream.length * 8L;
            long minBitsRequired = getEffectiveSp80022MinBits();

            if (bitstreamLengthBits < minBitsRequired) {
                throw new NistException(
                        String.format(
                                "Need at least %d bits, got %d",
                                minBitsRequired, bitstreamLengthBits));
            }

            int maxBytes = getEffectiveSp80022MaxBytes();
            UUID testSuiteRunId = UUID.randomUUID();
            String batchId = events.getFirst().batchId;

            boolean singleSequence = bitstream.length <= maxBytes;

            if (singleSequence) {
                // Single-sequence mode
                transactionalSelf().updateJobMetadata(jobId, testSuiteRunId, 1);

                LOG.infof(
                        "SP 800-22 job %s: single-sequence mode, bytes=%d bits=%d",
                        jobId, bitstream.length, bitstreamLengthBits);

                Sp80022TestResponse grpcResult = runSp80022Tests(bitstream, bearerToken);
                List<NistTestResult> entitiesToPersist = new ArrayList<>();

                for (Sp80022TestResult test : grpcResult.getResultsList()) {
                    NistTestResult entity =
                            new NistTestResult(
                                    testSuiteRunId,
                                    test.getName(),
                                    test.getPassed(),
                                    test.getPValue(),
                                    windowStart,
                                    windowEnd);
                    entity.dataSampleSize = bitstreamLengthBits;
                    entity.bitsTested = bitstreamLengthBits;
                    entity.batchId = batchId;
                    entity.chunkIndex = 1;
                    entity.chunkCount = 1;
                    entity.aggregationMethod = "SINGLE_SEQUENCE";
                    entity.details =
                            test.hasWarning()
                                    ? ensureJsonDocument(test.getWarning(), "warning")
                                    : null;
                    entitiesToPersist.add(entity);
                }

                transactionalSelf().persistNistTestResultsBatch(entitiesToPersist);
                transactionalSelf().updateJobProgress(jobId, 1, 100);
            } else {
                // Multi-sequence mode
                int minBytesPerSequence = (int) Math.ceil(getEffectiveSp80022MinBits() / 8.0);
                int minSequences = 55;
                int maxSequencesBySize = bitstream.length / minBytesPerSequence;

                if (maxSequencesBySize < minSequences) {
                    throw new NistException(
                            String.format(
                                    "Multi-sequence mode requires >= %d sequences of >= %d bytes"
                                            + " each. Bitstream (%d bytes) can produce at most %d"
                                            + " sequences.",
                                    minSequences,
                                    minBytesPerSequence,
                                    bitstream.length,
                                    maxSequencesBySize));
                }

                int sequenceCount =
                        Math.max(minSequences, (bitstream.length + maxBytes - 1) / maxBytes);
                int sequenceLength = bitstream.length / sequenceCount;

                transactionalSelf().updateJobMetadata(jobId, testSuiteRunId, sequenceCount);

                LOG.infof(
                        "SP 800-22 job %s: multi-sequence mode, bytes=%d sequences=%d"
                                + " seqLength=%d",
                        jobId, bitstream.length, sequenceCount, sequenceLength);

                // Truncate to exact multiple of sequenceLength (§4.2.1)
                int usableLen = sequenceCount * sequenceLength;
                byte[] trimmed = Arrays.copyOf(bitstream, usableLen);

                for (int i = 0; i < sequenceCount; i++) {
                    int seqStart = i * sequenceLength;
                    int seqEnd = seqStart + sequenceLength;
                    byte[] sequence = Arrays.copyOfRange(trimmed, seqStart, seqEnd);
                    long seqBits = sequence.length * 8L;

                    Sp80022TestResponse grpcResult = runSp80022Tests(sequence, bearerToken);

                    List<NistTestResult> sequenceResults = new ArrayList<>();
                    for (Sp80022TestResult test : grpcResult.getResultsList()) {
                        NistTestResult entity =
                                new NistTestResult(
                                        testSuiteRunId,
                                        test.getName(),
                                        test.getPassed(),
                                        test.getPValue(),
                                        windowStart,
                                        windowEnd);
                        entity.dataSampleSize = seqBits;
                        entity.bitsTested = seqBits;
                        entity.batchId = batchId;
                        entity.chunkIndex = i + 1;
                        entity.chunkCount = sequenceCount;
                        entity.aggregationMethod = "MULTI_SEQUENCE_CHI2";
                        entity.details =
                                test.hasWarning()
                                        ? ensureJsonDocument(test.getWarning(), "warning")
                                        : null;
                        sequenceResults.add(entity);
                    }

                    transactionalSelf().persistNistTestResultsBatch(sequenceResults);

                    int progressPercent = (int) ((i + 1) * 100.0 / sequenceCount);
                    transactionalSelf().updateJobProgress(jobId, i + 1, progressPercent);

                    LOG.infof(
                            "SP 800-22 job %s: sequence %d/%d complete (%d%%)",
                            jobId, i + 1, sequenceCount, progressPercent);
                }
            }

            transactionalSelf().markJobCompleted(jobId);

            LOG.infof(
                    "SP 800-22 job %s completed successfully: runId=%s mode=%s",
                    jobId,
                    testSuiteRunId,
                    singleSequence ? "SINGLE_SEQUENCE" : "MULTI_SEQUENCE_CHI2");

        } catch (Exception e) {
            LOG.errorf(e, "SP 800-22 job %s failed", jobId);
            String errorMessage =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
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
     * Async worker for an SP 800-90B validation job.
     *
     * <p>Performs multi-point sampling: draws N independent 1M-byte samples at evenly-spaced
     * positions across the time window. Each sample is a NIST-valid SP 800-90B assessment.
     * The run-summary row applies conservative product-defined aggregation:
     * min(min_entropy) and AND(passed).
     *
     * @param jobId       job identifier to process
     * @param bearerToken bearer token for gRPC authentication (may be null)
     */
    public void processSp80090bValidationJob(UUID jobId, String bearerToken) {
        NistValidationJob job = NistValidationJob.findById(jobId);
        if (job == null) {
            LOG.errorf("SP 800-90B job %s not found — skipping", jobId);
            return;
        }

        transactionalSelf().markJobRunning(jobId);
        LOG.infof("SP 800-90B job %s started", jobId);

        try {

            Instant windowStart = job.windowStart;
            Instant windowEnd = job.windowEnd;

            List<EntropyData> events = EntropyData.findInTimeWindow(windowStart, windowEnd);
            if (events.isEmpty()) {
                throw new NistException("No entropy data in specified window");
            }

            byte[] bitstream = extractWhitenedBits(events);
            if (bitstream.length == 0) {
                throw new NistException("No usable entropy bitstream in specified window");
            }

            int maxBytes = getEffectiveSp80090bMaxBytes();
            long windowDurationSeconds =
                    computeWindowDurationSeconds(events, windowStart, windowEnd);
            List<int[]> samplePositions =
                    compute90BSamplePositions(bitstream.length, maxBytes, windowDurationSeconds);
            int sampleCount = samplePositions.size();

            UUID assessmentRunId = UUID.randomUUID();
            transactionalSelf().updateJob90BMetadata(jobId, assessmentRunId, sampleCount);

            LOG.infof(
                    "SP 800-90B job %s: totalBytes=%d samples=%d sampleSize=%d windowDuration=%ds",
                    jobId, bitstream.length, sampleCount, maxBytes, windowDurationSeconds);

            String batchId = events.getFirst().batchId;
            int bytesPerEvent = GrpcMappingService.EXPECTED_WHITENED_ENTROPY_BYTES;

            double worstMinEntropy = Double.MAX_VALUE;
            Sp80090bAssessmentResponse worstResponse = null;
            int worstSampleIndex = -1;
            boolean allPassed = true;
            long totalBits = 0L;

            for (int i = 0; i < sampleCount; i++) {
                int[] pos = samplePositions.get(i);
                int sampleStart = pos[0];
                int sampleEnd = pos[1];
                byte[] sample = Arrays.copyOfRange(bitstream, sampleStart, sampleEnd);

                // Resolve hwTimestampNs for first and last events contributing to this sample
                int firstEventIndex = sampleStart / bytesPerEvent;
                int lastEventIndex = Math.max(firstEventIndex, (sampleEnd - 1) / bytesPerEvent);
                Instant firstEventTs = resolveEventTimestamp(events, firstEventIndex);
                Instant lastEventTs = resolveEventTimestamp(events, lastEventIndex);

                LOG.infof(
                        "SP 800-90B job %s: processing sample %d/%d (%d bytes, range [%d,%d))",
                        jobId, i + 1, sampleCount, sample.length, sampleStart, sampleEnd);

                Sp80090bOutcome outcome =
                        assess90B(
                                sample,
                                batchId,
                                windowStart,
                                windowEnd,
                                bearerToken,
                                assessmentRunId);

                Nist90BResult entity = outcome.entity();
                entity.sampleIndex = i + 1;
                entity.sampleCount = sampleCount;
                entity.sampleByteOffsetStart = (long) sampleStart;
                entity.sampleByteOffsetEnd = (long) sampleEnd;
                entity.sampleFirstEventTimestamp = firstEventTs;
                entity.sampleLastEventTimestamp = lastEventTs;
                entity.assessmentScope = "NIST_SINGLE_SAMPLE";
                long actualSampleBytes = sampleEnd - sampleStart;
                entity.sampleSizeMeetsNistMinimum =
                        actualSampleBytes >= getEffectiveSp80090bMaxBytes();
                transactionalSelf().persist90BResult(entity);

                if (outcome.response().getMinEntropy() < worstMinEntropy) {
                    worstMinEntropy = outcome.response().getMinEntropy();
                    worstResponse = outcome.response();
                    worstSampleIndex = i;
                }

                allPassed &= outcome.response().getPassed();
                totalBits += entity.bitsTested != null ? entity.bitsTested : 0L;

                int progressPercent = (int) ((i + 1) * 100.0 / sampleCount);
                transactionalSelf().updateJobProgress(jobId, i + 1, progressPercent);

                LOG.infof(
                        "SP 800-90B job %s: sample %d/%d complete (%d%%) minEntropy=%.6f",
                        jobId, i + 1, sampleCount, progressPercent, entity.minEntropy);
            }

            // Run-summary row: product-defined conservative aggregation
            String summaryDetails;
            try {
                summaryDetails =
                        JSON_MAPPER
                                .createObjectNode()
                                .put("samplingStrategy", "evenly_spaced")
                                .put("samples", sampleCount)
                                .put("sampleSizeBytes", maxBytes)
                                .put("rule", "min_entropy=min(all),passed=AND(all)")
                                .put("estimatorSourceSample", worstSampleIndex)
                                .put(
                                        "note",
                                        "Product-defined conservative summary, not NIST-specified")
                                .toString();
            } catch (Exception e) {
                summaryDetails = null;
            }

            Nist90BResult summary =
                    new Nist90BResult(
                            batchId,
                            worstMinEntropy,
                            allPassed,
                            summaryDetails,
                            totalBits,
                            windowStart,
                            windowEnd);
            summary.assessmentRunId = assessmentRunId;
            summary.sampleCount = sampleCount;
            summary.isRunSummary = true;
            summary.assessmentScope = "PRODUCT_WINDOW_SUMMARY";
            transactionalSelf().persist90BResult(summary);

            transactionalSelf().writeEstimatorsForRun(assessmentRunId, worstResponse, worstSampleIndex);

            transactionalSelf().markJobCompleted(jobId);

            LOG.infof(
                    "SP 800-90B job %s completed successfully: runId=%s samples=%d",
                    jobId, assessmentRunId, sampleCount);

        } catch (Exception e) {
            LOG.errorf(e, "SP 800-90B job %s failed", jobId);
            String errorMessage =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
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
        String validationMode =
                firstTest.aggregationMethod != null
                        ? firstTest.aggregationMethod
                        : "SINGLE_SEQUENCE";

        // For multi-sequence runs, aggregate via chi-square per test name
        if ("MULTI_SEQUENCE_CHI2".equals(validationMode)) {
            Map<String, List<Double>> pValuesByTest = new LinkedHashMap<>();
            Map<String, Integer> passCountByTest = new LinkedHashMap<>();
            long totalBitsTested = 0L;
            Set<Integer> seenChunks = new HashSet<>();

            for (NistTestResult test : tests) {
                pValuesByTest
                        .computeIfAbsent(test.testName, k -> new ArrayList<>())
                        .add(test.pValue != null ? test.pValue : 0.0);
                if (test.passed) {
                    passCountByTest.merge(test.testName, 1, Integer::sum);
                } else {
                    passCountByTest.putIfAbsent(test.testName, 0);
                }
                if (test.chunkIndex != null && !seenChunks.contains(test.chunkIndex)) {
                    seenChunks.add(test.chunkIndex);
                    totalBitsTested += test.bitsTested != null ? test.bitsTested : 0L;
                }
            }

            int sequenceCount = seenChunks.size();
            double alpha = 0.01;
            double proportionThreshold =
                    1.0 - alpha - 3.0 * Math.sqrt(alpha * (1.0 - alpha) / sequenceCount);

            List<NISTTestResultDTO> aggregatedDTOs = new ArrayList<>();
            int passedCount = 0;
            for (Map.Entry<String, List<Double>> entry : pValuesByTest.entrySet()) {
                String testName = entry.getKey();
                double chi2PValue = computeChiSquareUniformity(entry.getValue());
                boolean chi2Passed = chi2PValue >= 0.0001;

                int passes = passCountByTest.getOrDefault(testName, 0);
                double proportion = (double) passes / sequenceCount;
                boolean proportionPassed = proportion >= proportionThreshold;

                boolean testPassed = chi2Passed && proportionPassed;
                if (testPassed) passedCount++;

                String details = null;
                if (!proportionPassed) {
                    details =
                            ensureJsonDocument(
                                    String.format(
                                            "passRatio=%.4f threshold=%.4f chi2PValue=%.6f",
                                            proportion, proportionThreshold, chi2PValue),
                                    "proportion_fail");
                }

                aggregatedDTOs.add(
                        new NISTTestResultDTO(
                                testName,
                                testPassed,
                                chi2PValue,
                                testPassed ? "PASS" : "FAIL",
                                firstTest.executedAt,
                                details,
                                "MULTI_SEQUENCE_CHI2",
                                null,
                                null,
                                null));
            }

            int totalTests = aggregatedDTOs.size();
            int failedCount = totalTests - passedCount;
            double passRate = totalTests > 0 ? (double) passedCount / totalTests : 0.0;

            return new NISTSuiteResultDTO(
                    aggregatedDTOs,
                    totalTests,
                    passedCount,
                    failedCount,
                    passRate,
                    failedCount == 0,
                    firstTest.executedAt,
                    totalBitsTested,
                    new TimeWindowDTO(
                            firstTest.windowStart,
                            firstTest.windowEnd,
                            Duration.between(firstTest.windowStart, firstTest.windowEnd).toHours()),
                    validationMode);
        }

        // Single-sequence mode: return results directly
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
                        Duration.between(firstTest.windowStart, firstTest.windowEnd).toHours()),
                validationMode);
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

        // Fetch the canonical run-summary row (isRunSummary=true). Absent for
        // incomplete runs and runs that predate the run-summary schema migration.
        Nist90BResult summary =
                Nist90BResult.find(
                                "assessmentRunId = ?1 AND isRunSummary = true", job.assessmentRunId)
                        .firstResult();
        if (summary == null) {
            throw new ValidationException(
                    "No run-summary row found for assessment run of job "
                            + jobId
                            + "; the run may predate the run-summary schema migration or may still"
                            + " be in progress");
        }
        return summary.toDTO();
    }


    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void updateJobProgress(UUID jobId, int currentChunk, int progressPercent) {
        NistValidationJob job = NistValidationJob.findById(jobId);

        if (job != null) {
            job.currentChunk = currentChunk;
            job.progressPercent = progressPercent;
            job.persist();
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markJobRunning(UUID jobId) {
        NistValidationJob job = NistValidationJob.findById(jobId);
        if (job != null) {
            job.status = JobStatus.RUNNING;
            job.startedAt = Instant.now();
            job.persist();
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markJobCompleted(UUID jobId) {
        NistValidationJob job = NistValidationJob.findById(jobId);
        if (job != null) {
            job.status = JobStatus.COMPLETED;
            job.completedAt = Instant.now();
            job.progressPercent = 100;
            job.persist();
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void updateJobMetadata(UUID jobId, UUID testSuiteRunId, int totalChunks) {
        NistValidationJob job = NistValidationJob.findById(jobId);
        if (job != null) {
            job.testSuiteRunId = testSuiteRunId;
            job.totalChunks = totalChunks;
            job.persist();
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void updateJob90BMetadata(UUID jobId, UUID assessmentRunId, int totalChunks) {
        NistValidationJob job = NistValidationJob.findById(jobId);
        if (job != null) {
            job.assessmentRunId = assessmentRunId;
            job.totalChunks = totalChunks;
            job.persist();
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void persist90BResult(Nist90BResult entity) {
        em.persist(entity);
        em.flush();
    }
}
