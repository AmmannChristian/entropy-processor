/* (C)2026 */
package com.ammann.entropy.service;

import com.ammann.entropy.dto.EntropyComparisonResultDTO;
import com.ammann.entropy.dto.EntropyComparisonSummaryDTO;
import com.ammann.entropy.enumeration.EntropySourceType;
import com.ammann.entropy.enumeration.JobStatus;
import com.ammann.entropy.exception.NistException;
import com.ammann.entropy.grpc.proto.sp80022.MutinySp80022TestServiceGrpc;
import com.ammann.entropy.grpc.proto.sp80022.Sp80022TestRequest;
import com.ammann.entropy.grpc.proto.sp80022.Sp80022TestResponse;
import com.ammann.entropy.grpc.proto.sp80022.Sp80022TestResult;
import com.ammann.entropy.grpc.proto.sp80022.Sp80022TestService;
import com.ammann.entropy.grpc.proto.sp80090b.MutinySp80090bAssessmentServiceGrpc;
import com.ammann.entropy.grpc.proto.sp80090b.Sp80090bAssessmentRequest;
import com.ammann.entropy.grpc.proto.sp80090b.Sp80090bAssessmentResponse;
import com.ammann.entropy.grpc.proto.sp80090b.Sp80090bAssessmentService;
import com.ammann.entropy.model.EntropyComparisonResult;
import com.ammann.entropy.model.EntropyComparisonRun;
import com.ammann.entropy.model.EntropyData;
import com.google.protobuf.ByteString;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Collects one entropy sample per source (BASELINE, HARDWARE, MIXED) and evaluates
 * each through NIST SP 800-22, NIST SP 800-90B, and custom entropy metrics to support
 * empirical comparison for the thesis.
 *
 * <p>Transaction design: this service is intentionally NOT {@code @Transactional}. Each
 * database write uses {@link QuarkusTransaction#requiringNew()} so long gRPC calls do not
 * hold a database connection open.
 */
@ApplicationScoped
public class EntropyComparisonService {

    private static final Logger LOG = Logger.getLogger(EntropyComparisonService.class);

    private static final long SP80022_MIN_BITS = 1_000_000L;
    private static final int SP80090B_MIN_BYTES = 1_000;
    private static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    @GrpcClient("sp80022-test-service")
    MutinySp80022TestServiceGrpc.MutinySp80022TestServiceStub sp80022Client;

    @GrpcClient("sp80090b-assessment-service")
    MutinySp80090bAssessmentServiceGrpc.MutinySp80090bAssessmentServiceStub sp80090bClient;

    // Visible for testing only
    private Sp80022TestService sp80022Override;

    // Visible for testing only
    private Sp80090bAssessmentService sp80090bOverride;

    // Visible for testing only: fixed bytes returned by collectFromUrandom()
    private byte[] urandomOverride;

    private final KernelEntropyWriterService kernelWriterService;
    private final OidcClientService oidcClientService;

    @ConfigProperty(name = "entropy.comparison.enabled", defaultValue = "true")
    boolean comparisonEnabled;

    @ConfigProperty(name = "entropy.comparison.sample.size.bytes.sp80022", defaultValue = "4194304")
    int sp80022SampleSize;

    @ConfigProperty(
            name = "entropy.comparison.sample.size.bytes.sp80090b",
            defaultValue = "4194304")
    int sp80090bSampleSize;

    @ConfigProperty(name = "entropy.comparison.sample.size.bytes.metrics", defaultValue = "1048576")
    int metricsSampleSize;

    private int maxSampleSize;

    @Inject
    public EntropyComparisonService(
            KernelEntropyWriterService kernelWriterService, OidcClientService oidcClientService) {
        this.kernelWriterService = kernelWriterService;
        this.oidcClientService = oidcClientService;
    }

    @PostConstruct
    void init() {
        maxSampleSize =
                Math.max(sp80022SampleSize, Math.max(sp80090bSampleSize, metricsSampleSize));
        LOG.infof(
                "EntropyComparisonService initialized: sp80022=%d sp80090b=%d metrics=%d max=%d",
                sp80022SampleSize, sp80090bSampleSize, metricsSampleSize, maxSampleSize);
    }

    /**
     * Scheduled comparison run. Skips if another run is still in progress.
     */
    @Scheduled(
            cron = "${entropy.comparison.schedule.cron}",
            concurrentExecution = ConcurrentExecution.SKIP)
    @ActivateRequestContext
    public void runComparison() {
        if (!comparisonEnabled) {
            LOG.debug("Entropy comparison disabled");
            return;
        }

        LOG.info("Starting entropy source comparison run");

        Long runId = QuarkusTransaction.requiringNew().call(this::createAndPersistRun);

        try {
            // BASELINE
            byte[] baselineSample = collectMaxSample(EntropySourceType.BASELINE);
            processSource(runId, EntropySourceType.BASELINE, baselineSample, false, null);

            // HARDWARE
            byte[] hardwareSample = collectMaxSample(EntropySourceType.HARDWARE);
            processSource(runId, EntropySourceType.HARDWARE, hardwareSample, false, null);

            // MIXED
            MixedSample mixed = collectMixedMaxSample();
            processSource(
                    runId,
                    EntropySourceType.MIXED,
                    mixed.data(),
                    mixed.mixedValid(),
                    mixed.injectionTs());

            boolean mv = mixed.mixedValid();
            Instant its = mixed.injectionTs();
            QuarkusTransaction.requiringNew().run(() -> markCompleted(runId, mv, its));

            LOG.infof("Comparison run %d completed", runId);

        } catch (Exception e) {
            LOG.errorf(e, "Comparison run %d failed", runId);
            QuarkusTransaction.requiringNew().run(() -> markFailed(runId, e));
        }
    }

    // -------------------------------------------------------------------------
    // Run lifecycle helpers
    // -------------------------------------------------------------------------

    private Long createAndPersistRun() {
        EntropyComparisonRun run = new EntropyComparisonRun();
        run.runTimestamp = Instant.now();
        run.status = JobStatus.RUNNING;
        run.sp80022SampleSizeBytes = sp80022SampleSize;
        run.sp80090bSampleSizeBytes = sp80090bSampleSize;
        run.metricsSampleSizeBytes = metricsSampleSize;
        run.persist();
        LOG.infof("Created comparison run id=%d", run.id);
        return run.id;
    }

    private void markCompleted(Long runId, boolean mixedValid, Instant injectionTs) {
        EntropyComparisonRun run = EntropyComparisonRun.findById(runId);
        if (run == null) return;
        run.status = JobStatus.COMPLETED;
        run.completedAt = Instant.now();
        run.mixedValid = mixedValid;
        run.mixedInjectionTimestamp = injectionTs;
        run.persist();
    }

    private void markFailed(Long runId, Exception e) {
        EntropyComparisonRun run = EntropyComparisonRun.findById(runId);
        if (run == null) return;
        run.status = JobStatus.FAILED;
        run.completedAt = Instant.now();
        run.persist();
    }

    // -------------------------------------------------------------------------
    // Sample collection
    // -------------------------------------------------------------------------

    private byte[] collectMaxSample(EntropySourceType type) {
        return switch (type) {
            case BASELINE -> collectFromUrandom();
            case HARDWARE -> collectFromHardware();
            case MIXED -> collectFromUrandom(); // fallback, real MIXED uses collectMixedMaxSample()
        };
    }

    private byte[] collectFromUrandom() {
        if (urandomOverride != null) {
            return urandomOverride;
        }
        byte[] buf = new byte[maxSampleSize];
        int total = 0;
        try (FileInputStream fis = new FileInputStream("/dev/urandom")) {
            while (total < maxSampleSize) {
                int read = fis.read(buf, total, maxSampleSize - total);
                if (read < 0) break;
                total += read;
            }
        } catch (IOException e) {
            LOG.warnf(e, "Failed reading /dev/urandom after %d bytes", total);
        }
        return total == maxSampleSize ? buf : Arrays.copyOf(buf, total);
    }

    private byte[] collectFromHardware() {
        Instant now = Instant.now();
        Instant start = now.minus(5, ChronoUnit.MINUTES);
        List<EntropyData> events = EntropyData.findInTimeWindow(start, now);

        if (events.isEmpty()) {
            LOG.warn("No hardware entropy events in last 5 minutes");
            return new byte[0];
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(maxSampleSize);
        for (EntropyData event : events) {
            byte[] w = event.whitenedEntropy;
            if (w != null && w.length > 0) {
                out.write(w, 0, w.length);
                if (out.size() >= maxSampleSize) break;
            }
        }

        byte[] all = out.toByteArray();
        return all.length > maxSampleSize ? Arrays.copyOf(all, maxSampleSize) : all;
    }

    private MixedSample collectMixedMaxSample() {
        if (!kernelWriterService.isOperational()) {
            LOG.warn(
                    "KernelEntropyWriterService not operational; MIXED sample falls back to"
                            + " BASELINE");
            return new MixedSample(collectFromUrandom(), false, null);
        }

        kernelWriterService.feedKernelEntropy();

        try {
            Thread.sleep(20);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        Instant lastTs = kernelWriterService.getLastSuccessfulInjectionTimestamp();
        boolean mixedValid = lastTs != null && lastTs.isAfter(Instant.now().minusSeconds(5));
        if (!mixedValid) {
            LOG.warn("Hardware injection not confirmed recent; mixedValid=false");
        }

        return new MixedSample(collectFromUrandom(), mixedValid, lastTs);
    }

    record MixedSample(byte[] data, boolean mixedValid, Instant injectionTs) {}

    // -------------------------------------------------------------------------
    // Per-source processing
    // -------------------------------------------------------------------------

    private void processSource(
            Long runId,
            EntropySourceType sourceType,
            byte[] maxSample,
            boolean mixedValid,
            Instant injectionTs) {

        int collected = maxSample.length;

        byte[] sp22Slice = Arrays.copyOf(maxSample, Math.min(collected, sp80022SampleSize));
        byte[] sp90bSlice = Arrays.copyOf(maxSample, Math.min(collected, sp80090bSampleSize));
        byte[] metSlice = Arrays.copyOf(maxSample, Math.min(collected, metricsSampleSize));

        EntropyComparisonResult result = new EntropyComparisonResult();
        result.comparisonRunId = runId;
        result.sourceType = sourceType;
        result.bytesCollected = collected;
        result.sp80022BytesUsed = sp22Slice.length;
        result.sp80090bBytesUsed = sp90bSlice.length;
        result.metricsBytesUsed = metSlice.length;

        // SP 800-22
        long sp22Bits = sp22Slice.length * 8L;
        if (sp22Bits < SP80022_MIN_BITS) {
            result.nist22Status = "INSUFFICIENT_DATA";
            LOG.infof(
                    "SP 800-22 skipped for %s: %d bits < %d minimum",
                    sourceType, sp22Bits, SP80022_MIN_BITS);
        } else {
            try {
                Sp80022TestResponse r = runSp80022Tests(sp22Slice);
                List<Sp80022TestResult> tests = r.getResultsList();

                // Heuristic: tests with p-value == 0 and not passed are likely skipped
                List<Sp80022TestResult> executed =
                        tests.stream().filter(t -> t.getPValue() > 0 || t.getPassed()).toList();
                List<Sp80022TestResult> skipped =
                        tests.stream().filter(t -> t.getPValue() == 0 && !t.getPassed()).toList();

                long passed = executed.stream().filter(Sp80022TestResult::getPassed).count();
                result.nist22ExecutedTests = executed.size();
                result.nist22SkippedTests = skipped.size();
                result.nist22PassRate =
                        executed.isEmpty()
                                ? null
                                : BigDecimal.valueOf(passed * 100.0 / executed.size())
                                        .setScale(2, RoundingMode.HALF_UP);
                result.nist22PValueMean = meanPValue(executed);
                result.nist22PValueMin = minPValue(executed);
                result.nist22Status =
                        (!executed.isEmpty() && passed == executed.size()) ? "PASSED" : "FAILED";

                LOG.infof(
                        "SP 800-22 %s: executed=%d skipped=%d passed=%d status=%s",
                        sourceType, executed.size(), skipped.size(), passed, result.nist22Status);

            } catch (NistException | StatusRuntimeException e) {
                LOG.warnf(e, "SP 800-22 comparison failed for %s", sourceType);
                result.nist22Status = "ERROR";
            }
        }

        // SP 800-90B
        if (sp90bSlice.length < SP80090B_MIN_BYTES) {
            result.nist90bStatus = "INSUFFICIENT_DATA";
            LOG.infof(
                    "SP 800-90B skipped for %s: %d bytes < %d minimum",
                    sourceType, sp90bSlice.length, SP80090B_MIN_BYTES);
        } else {
            try {
                Sp80090bAssessmentResponse r = runSp80090bAssessment(sp90bSlice);
                result.minEntropyEstimate =
                        BigDecimal.valueOf(r.getMinEntropy()).setScale(8, RoundingMode.HALF_UP);
                result.nist90bStatus = r.getPassed() ? "PASSED" : "FAILED";
                LOG.infof(
                        "SP 800-90B %s: minEntropy=%s status=%s",
                        sourceType, result.minEntropyEstimate, result.nist90bStatus);
            } catch (NistException | StatusRuntimeException e) {
                LOG.warnf(e, "SP 800-90B comparison failed for %s", sourceType);
                result.nist90bStatus = "ERROR";
            }
        }

        // Custom entropy metrics
        result.shannonEntropy = bd(computeShannonEntropy(metSlice));
        result.renyiEntropy = bd(computeRenyiEntropy(metSlice, 2.0));
        result.sampleEntropy = bd(computeSampleEntropy(metSlice, 10_000));

        LOG.infof(
                "Metrics %s: shannon=%s renyi=%s sampleEn=%s",
                sourceType, result.shannonEntropy, result.renyiEntropy, result.sampleEntropy);

        QuarkusTransaction.requiringNew().run(result::persist);
    }

    // -------------------------------------------------------------------------
    // gRPC helpers
    // -------------------------------------------------------------------------

    private Sp80022TestResponse runSp80022Tests(byte[] bitstream) {
        Sp80022TestRequest request =
                Sp80022TestRequest.newBuilder()
                        .setBitstream(ByteString.copyFrom(bitstream))
                        .build();

        if (sp80022Override != null) {
            return sp80022Override.runTestSuite(request).await().atMost(Duration.ofMinutes(10));
        }

        String token = resolveToken(null, "SP 800-22 comparison");
        var client = sp80022Client;
        if (token != null) client = withBearerToken(client, token);

        client =
                client.withMaxOutboundMessageSize(bitstream.length + 512 * 1024)
                        .withMaxInboundMessageSize(2 * 1024 * 1024);

        return client.runTestSuite(request).await().atMost(Duration.ofMinutes(10));
    }

    private Sp80090bAssessmentResponse runSp80090bAssessment(byte[] data) {
        Sp80090bAssessmentRequest request =
                Sp80090bAssessmentRequest.newBuilder()
                        .setData(ByteString.copyFrom(data))
                        .setBitsPerSymbol(8)
                        .setIidMode(true)
                        .setNonIidMode(true)
                        .setVerbosity(1)
                        .build();

        if (sp80090bOverride != null) {
            return sp80090bOverride.assessEntropy(request).await().atMost(Duration.ofMinutes(10));
        }

        String token = resolveToken(null, "SP 800-90B comparison");
        var client = sp80090bClient;
        if (token != null) client = withBearerToken(client, token);

        client =
                client.withMaxOutboundMessageSize(data.length + 512 * 1024)
                        .withMaxInboundMessageSize(2 * 1024 * 1024);

        return client.assessEntropy(request).await().atMost(Duration.ofMinutes(10));
    }

    private String resolveToken(String bearerToken, String serviceName) {
        if (bearerToken != null && !bearerToken.isBlank()) {
            LOG.debugf("Using propagated bearer token for %s call", serviceName);
            return bearerToken;
        }

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

    private <T extends AbstractStub<T>> T withBearerToken(T client, String token) {
        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION_KEY, "Bearer " + token);
        return client.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    // -------------------------------------------------------------------------
    // Entropy computation helpers
    // -------------------------------------------------------------------------

    private double computeShannonEntropy(byte[] data) {
        if (data.length == 0) return 0.0;
        int[] freq = new int[256];
        for (byte b : data) freq[b & 0xFF]++;
        double entropy = 0.0;
        double n = data.length;
        for (int count : freq) {
            if (count > 0) {
                double p = count / n;
                entropy -= p * (Math.log(p) / Math.log(2));
            }
        }
        return entropy;
    }

    private double computeRenyiEntropy(byte[] data, double alpha) {
        if (data.length == 0) return 0.0;
        if (Math.abs(alpha - 1.0) < 1e-9) return computeShannonEntropy(data);
        int[] freq = new int[256];
        for (byte b : data) freq[b & 0xFF]++;
        double n = data.length;
        double sum = 0.0;
        for (int count : freq) {
            if (count > 0) {
                double p = count / n;
                sum += Math.pow(p, alpha);
            }
        }
        return (1.0 / (1.0 - alpha)) * (Math.log(sum) / Math.log(2));
    }

    private double computeSampleEntropy(byte[] data, int maxBytes) {
        // Guard for quadratic-time computation: truncate to maxBytes before processing.
        byte[] d = data.length > maxBytes ? Arrays.copyOf(data, maxBytes) : data;
        int n = d.length;
        int m = 2;
        if (n < m + 2) return 0.0;

        long A = 0; // (m+1)-length matches
        long B = 0; // m-length matches

        for (int i = 0; i < n - m; i++) {
            for (int j = i + 1; j < n - m; j++) {
                // Check m-length template match
                boolean mMatch = true;
                for (int k = 0; k < m; k++) {
                    if (d[i + k] != d[j + k]) {
                        mMatch = false;
                        break;
                    }
                }
                if (mMatch) {
                    B++;
                    // Check (m+1)-length match
                    if (d[i + m] == d[j + m]) A++;
                }
            }
        }

        if (A == 0 || B == 0) return 0.0;
        return -Math.log((double) A / B);
    }

    // -------------------------------------------------------------------------
    // Utility helpers
    // -------------------------------------------------------------------------

    private BigDecimal meanPValue(List<Sp80022TestResult> tests) {
        if (tests.isEmpty()) return null;
        double mean =
                tests.stream().mapToDouble(Sp80022TestResult::getPValue).average().orElse(0.0);
        return BigDecimal.valueOf(mean).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal minPValue(List<Sp80022TestResult> tests) {
        if (tests.isEmpty()) return null;
        double min = tests.stream().mapToDouble(Sp80022TestResult::getPValue).min().orElse(0.0);
        return BigDecimal.valueOf(min).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal bd(double value) {
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public List<EntropyComparisonRun> getRecentRuns(int limit) {
        return EntropyComparisonRun.findRecent(limit);
    }

    public List<EntropyComparisonResult> getResultsForRun(Long runId) {
        return EntropyComparisonResult.findByRunId(runId);
    }

    /** Visible for testing only: override SP 800-22 gRPC stub. */
    void setSp80022Override(Sp80022TestService override) {
        this.sp80022Override = override;
    }

    /** Visible for testing only: override SP 800-90B gRPC stub. */
    void setSp80090bOverride(Sp80090bAssessmentService override) {
        this.sp80090bOverride = override;
    }

    /** Visible for testing only: override urandom sample bytes and configure small sample sizes. */
    void configureSampleOverrideForTesting(byte[] fixedBytes) {
        this.urandomOverride = fixedBytes;
        this.sp80022SampleSize = fixedBytes.length;
        this.sp80090bSampleSize = fixedBytes.length;
        this.metricsSampleSize = fixedBytes.length;
        this.maxSampleSize = fixedBytes.length;
    }

    /** Visible for testing only: set individual suite sample sizes independently of urandom override. */
    void configureSampleSizesForTesting(int sp80022Size, int sp90bSize, int metricsSize) {
        this.sp80022SampleSize = sp80022Size;
        this.sp80090bSampleSize = sp90bSize;
        this.metricsSampleSize = metricsSize;
        this.maxSampleSize = Math.max(sp80022Size, Math.max(sp90bSize, metricsSize));
    }

    /** Visible for testing only: clear urandom override so real /dev/urandom is used. */
    void clearUrandomOverride() {
        this.urandomOverride = null;
    }

    public com.ammann.entropy.dto.EntropyComparisonSummaryDTO getSummary() {
        List<EntropyComparisonRun> runs = EntropyComparisonRun.findRecent(1);
        if (runs.isEmpty()) {
            return new com.ammann.entropy.dto.EntropyComparisonSummaryDTO(
                    null,
                    null,
                    null,
                    List.of(),
                    EntropyComparisonRun.countByStatus(JobStatus.COMPLETED));
        }

        EntropyComparisonRun latest = runs.get(0);
        List<EntropyComparisonResultDTO> resultDTOs =
                EntropyComparisonResult.findByRunId(latest.id).stream()
                        .map(EntropyComparisonResultDTO::from)
                        .toList();

        return new EntropyComparisonSummaryDTO(
                latest.id,
                latest.runTimestamp,
                latest.status != null ? latest.status.name() : null,
                resultDTOs,
                EntropyComparisonRun.countByStatus(JobStatus.COMPLETED));
    }
}
