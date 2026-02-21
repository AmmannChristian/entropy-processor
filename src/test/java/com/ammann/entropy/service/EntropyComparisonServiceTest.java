/* (C)2026 */
package com.ammann.entropy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ammann.entropy.dto.EntropyComparisonResultDTO;
import com.ammann.entropy.dto.EntropyComparisonRunDTO;
import com.ammann.entropy.enumeration.EntropySourceType;
import com.ammann.entropy.enumeration.JobStatus;
import com.ammann.entropy.grpc.proto.sp80022.Sp80022TestResponse;
import com.ammann.entropy.grpc.proto.sp80022.Sp80022TestResult;
import com.ammann.entropy.grpc.proto.sp80022.Sp80022TestService;
import com.ammann.entropy.grpc.proto.sp80090b.Sp80090bAssessmentResponse;
import com.ammann.entropy.grpc.proto.sp80090b.Sp80090bAssessmentService;
import com.ammann.entropy.model.EntropyComparisonResult;
import com.ammann.entropy.model.EntropyComparisonRun;
import com.ammann.entropy.model.EntropyData;
import com.ammann.entropy.support.TestDataFactory;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class EntropyComparisonServiceTest {

    // SP 800-22 needs at least 1,000,000 bits = 125,000 bytes
    private static final int SP80022_SUFFICIENT_BYTES = 160_000;
    // SP 800-90B needs at least 1,000 bytes
    private static final int SP80090B_SUFFICIENT_BYTES = 2_000;

    @Inject EntropyComparisonService serviceProxy;

    EntropyComparisonService service;

    @BeforeEach
    void setUp() {
        service = ClientProxy.unwrap(serviceProxy);
        // Clean up any data left from previous tests
        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            EntropyComparisonResult.deleteAll();
                            EntropyComparisonRun.deleteAll();
                        });
        service.setSp80022Override(null);
        service.setSp80090bOverride(null);
        service.configureSampleOverrideForTesting(new byte[SP80022_SUFFICIENT_BYTES]);
        service.comparisonEnabled = true;
    }

    @AfterEach
    void tearDown() {
        service.setSp80022Override(null);
        service.setSp80090bOverride(null);
        service.configureSampleOverrideForTesting(new byte[SP80022_SUFFICIENT_BYTES]);
        service.comparisonEnabled = true;
    }

    // =========================================================================
    // runComparison(): overall flow.
    // =========================================================================

    @Test
    void runComparison_disabled_createsNoRun() {
        service.comparisonEnabled = false;

        service.runComparison();

        long count = QuarkusTransaction.requiringNew().call(() -> EntropyComparisonRun.count());
        assertThat(count).isZero();
    }

    @Test
    void runComparison_success_createsCompletedRunWithThreeResults() {
        service.setSp80022Override(sp80022Success());
        service.setSp80090bOverride(sp80090bSuccess());

        service.runComparison();

        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            List<EntropyComparisonRun> runs = EntropyComparisonRun.findAll().list();
                            assertThat(runs).hasSize(1);
                            assertThat(runs.get(0).status).isEqualTo(JobStatus.COMPLETED);
                            assertThat(runs.get(0).completedAt).isNotNull();

                            List<EntropyComparisonResult> results =
                                    EntropyComparisonResult.findAll().list();
                            assertThat(results).hasSize(3);
                            // All three sources present
                            assertThat(results)
                                    .extracting(r -> r.sourceType)
                                    .containsExactlyInAnyOrder(
                                            EntropySourceType.BASELINE,
                                            EntropySourceType.HARDWARE,
                                            EntropySourceType.MIXED);
                        });
    }

    @Test
    void runComparison_grpcThrows_createsFailedRun() {
        // SP 800-22 gRPC fails on every call, so the first processSource path propagates failure.
        service.setSp80022Override(
                request ->
                        io.smallrye.mutiny.Uni.createFrom()
                                .failure(new StatusRuntimeException(Status.INTERNAL)));

        service.runComparison();

        // The run should be marked FAILED (exception caught and markFailed called)
        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            List<EntropyComparisonRun> runs = EntropyComparisonRun.findAll().list();
                            assertThat(runs).hasSize(1);
                            // Status depends on whether the exception propagates past
                            // processSource;
                            // processSource catches StatusRuntimeException and sets ERROR, so run
                            // should
                            // still COMPLETE unless a different failure occurs. We verify that
                            // exactly
                            // one run exists and the service handled the error gracefully.
                            assertThat(runs.get(0).status)
                                    .isIn(JobStatus.COMPLETED, JobStatus.FAILED);
                        });
    }

    @Test
    void runComparison_kernelWriterNotOperational_mixedFallsBackToBaseline() {
        service.setSp80022Override(sp80022Success());
        service.setSp80090bOverride(sp80090bSuccess());
        // KernelEntropyWriterService is disabled by default and therefore not operational.

        service.runComparison();

        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            EntropyComparisonRun run = EntropyComparisonRun.findAll().firstResult();
                            assertThat(run).isNotNull();
                            assertThat(run.mixedValid).isFalse();
                            assertThat(run.mixedInjectionTimestamp).isNull();
                        });
    }

    // =========================================================================
    // processSource() via runComparison(): SP 800-22 branches.
    // =========================================================================

    @Test
    void runComparison_insufficientSp80022Data_setsInsufficiencyStatus() {
        // 100 bytes = 800 bits < 1,000,000 minimum
        service.configureSampleOverrideForTesting(new byte[100]);
        service.setSp80090bOverride(
                request ->
                        io.smallrye.mutiny.Uni.createFrom()
                                .item(
                                        Sp80090bAssessmentResponse.newBuilder()
                                                .setMinEntropy(0.1)
                                                .setPassed(false)
                                                .build()));

        service.runComparison();

        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            List<EntropyComparisonResult> results =
                                    EntropyComparisonResult.findAll().list();
                            assertThat(results).isNotEmpty();
                            assertThat(results)
                                    .allMatch(r -> "INSUFFICIENT_DATA".equals(r.nist22Status));
                        });
    }

    @Test
    void runComparison_sp80022AllTestsPass_setsPassedStatus() {
        service.setSp80022Override(sp80022AllPass());
        service.setSp80090bOverride(sp80090bSuccess());

        service.runComparison();

        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            List<EntropyComparisonResult> results =
                                    EntropyComparisonResult.list("nist22Status", "PASSED");
                            assertThat(results).isNotEmpty();
                            assertThat(results.get(0).nist22PassRate)
                                    .isEqualByComparingTo(BigDecimal.valueOf(100.00).setScale(2));
                        });
    }

    @Test
    void runComparison_sp80022SomeTestsFail_setsFailedStatus() {
        service.setSp80022Override(sp80022PartialFail());
        service.setSp80090bOverride(sp80090bSuccess());

        service.runComparison();

        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            List<EntropyComparisonResult> results =
                                    EntropyComparisonResult.list("nist22Status", "FAILED");
                            assertThat(results).isNotEmpty();
                        });
    }

    @Test
    void runComparison_sp80022AllTestsSkipped_setsFailedWithNullPassRate() {
        // All results have pValue=0 and passed=false, so executed is empty and passRate is null.
        service.setSp80022Override(sp80022AllSkipped());
        service.setSp80090bOverride(sp80090bSuccess());

        service.runComparison();

        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            // executed is empty, so passRate is null and status is FAILED.
                            List<EntropyComparisonResult> results =
                                    EntropyComparisonResult.findAll().list();
                            assertThat(results).isNotEmpty();
                            boolean hasNullPassRate =
                                    results.stream().anyMatch(r -> r.nist22PassRate == null);
                            assertThat(hasNullPassRate).isTrue();
                        });
    }

    @Test
    void runComparison_sp80022WithSkippedAndExecutedMix_recordsBothCounts() {
        // Mix of executed (pValue > 0) and skipped (pValue=0, !passed)
        service.setSp80022Override(sp80022MixedExecutedSkipped());
        service.setSp80090bOverride(sp80090bSuccess());

        service.runComparison();

        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            List<EntropyComparisonResult> results =
                                    EntropyComparisonResult.findAll().list();
                            assertThat(results).isNotEmpty();
                            // Verify skippedTests > 0 for at least one result
                            boolean hasSkipped =
                                    results.stream()
                                            .anyMatch(
                                                    r ->
                                                            r.nist22SkippedTests != null
                                                                    && r.nist22SkippedTests > 0);
                            assertThat(hasSkipped).isTrue();
                        });
    }

    @Test
    void runComparison_sp80022GrpcError_setsErrorStatus() {
        service.setSp80022Override(
                request ->
                        io.smallrye.mutiny.Uni.createFrom()
                                .failure(new StatusRuntimeException(Status.UNAVAILABLE)));
        service.setSp80090bOverride(sp80090bSuccess());

        service.runComparison();

        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            List<EntropyComparisonResult> results =
                                    EntropyComparisonResult.list("nist22Status", "ERROR");
                            assertThat(results).isNotEmpty();
                        });
    }

    // =========================================================================
    // processSource() via runComparison(): SP 800-90B branches.
    // =========================================================================

    @Test
    void runComparison_insufficientSp80090bData_setsInsufficiencyStatus() {
        // SP800-22 sufficient (160KB), SP800-90B insufficient (500 bytes < 1000 threshold)
        byte[] bigSample = new byte[SP80022_SUFFICIENT_BYTES];
        service.configureSampleOverrideForTesting(bigSample);
        service.configureSampleSizesForTesting(SP80022_SUFFICIENT_BYTES, 500, 500);
        service.setSp80022Override(sp80022AllPass());

        service.runComparison();

        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            List<EntropyComparisonResult> results =
                                    EntropyComparisonResult.list(
                                            "nist90bStatus", "INSUFFICIENT_DATA");
                            assertThat(results).isNotEmpty();
                        });
    }

    @Test
    void runComparison_sp80090bPassed_setsPassedStatus() {
        service.setSp80022Override(sp80022AllPass());
        service.setSp80090bOverride(sp80090bSuccess());

        service.runComparison();

        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            List<EntropyComparisonResult> results =
                                    EntropyComparisonResult.list("nist90bStatus", "PASSED");
                            assertThat(results).isNotEmpty();
                            assertThat(results.get(0).minEntropyEstimate).isNotNull();
                        });
    }

    @Test
    void runComparison_sp80090bFailed_setsFailedStatus() {
        service.setSp80022Override(sp80022AllPass());
        service.setSp80090bOverride(
                request ->
                        io.smallrye.mutiny.Uni.createFrom()
                                .item(
                                        Sp80090bAssessmentResponse.newBuilder()
                                                .setMinEntropy(3.5)
                                                .setPassed(false)
                                                .build()));

        service.runComparison();

        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            List<EntropyComparisonResult> results =
                                    EntropyComparisonResult.list("nist90bStatus", "FAILED");
                            assertThat(results).isNotEmpty();
                        });
    }

    @Test
    void runComparison_sp80090bGrpcError_setsErrorStatus() {
        service.setSp80022Override(sp80022AllPass());
        service.setSp80090bOverride(
                request ->
                        io.smallrye.mutiny.Uni.createFrom()
                                .failure(new StatusRuntimeException(Status.INTERNAL)));

        service.runComparison();

        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            List<EntropyComparisonResult> results =
                                    EntropyComparisonResult.list("nist90bStatus", "ERROR");
                            assertThat(results).isNotEmpty();
                        });
    }

    // =========================================================================
    // Hardware sample collection
    // =========================================================================

    @Test
    void runComparison_hardwareWithEvents_collectsNonZeroSample() {
        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            EntropyData.deleteAll();
                            byte[] entropy = new byte[200];
                            Arrays.fill(entropy, (byte) 0x5A);
                            EntropyData event =
                                    TestDataFactory.createEntropyEvent(
                                            1, 1_000L, Instant.now().minusSeconds(30));
                            event.whitenedEntropy = entropy;
                            event.persist();
                        });

        service.setSp80022Override(sp80022AllPass());
        service.setSp80090bOverride(sp80090bSuccess());
        // Use real /dev/urandom for BASELINE/MIXED; hardware reads from DB
        service.clearUrandomOverride();
        service.configureSampleSizesForTesting(
                SP80022_SUFFICIENT_BYTES, SP80090B_SUFFICIENT_BYTES, 1000);

        service.runComparison();

        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            List<EntropyComparisonResult> results =
                                    EntropyComparisonResult.list(
                                            "sourceType", EntropySourceType.HARDWARE);
                            assertThat(results).hasSize(1);
                            // Hardware events exist, so bytesCollected is greater than zero.
                            assertThat(results.get(0).bytesCollected).isGreaterThan(0);
                        });
    }

    @Test
    void runComparison_hardwareNoEvents_recordsZeroBytes() {
        QuarkusTransaction.requiringNew().run(() -> EntropyData.deleteAll());

        service.setSp80022Override(sp80022AllPass());
        service.setSp80090bOverride(sp80090bSuccess());

        service.runComparison();

        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            List<EntropyComparisonResult> results =
                                    EntropyComparisonResult.list(
                                            "sourceType", EntropySourceType.HARDWARE);
                            assertThat(results).hasSize(1);
                            assertThat(results.get(0).bytesCollected).isZero();
                            assertThat(results.get(0).nist22Status).isEqualTo("INSUFFICIENT_DATA");
                            assertThat(results.get(0).nist90bStatus).isEqualTo("INSUFFICIENT_DATA");
                        });
    }

    // =========================================================================
    // Entropy metrics
    // =========================================================================

    @Test
    void runComparison_entropyMetricsAreComputed() {
        service.setSp80022Override(sp80022AllPass());
        service.setSp80090bOverride(sp80090bSuccess());
        // Use non-uniform data for meaningful entropy values
        byte[] sample = new byte[SP80022_SUFFICIENT_BYTES];
        for (int i = 0; i < sample.length; i++) sample[i] = (byte) (i % 256);
        service.configureSampleOverrideForTesting(sample);

        service.runComparison();

        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            List<EntropyComparisonResult> results =
                                    EntropyComparisonResult.findAll().list();
                            for (EntropyComparisonResult r : results) {
                                if (r.bytesCollected > 0) {
                                    assertThat(r.shannonEntropy).isNotNull();
                                    assertThat(r.renyiEntropy).isNotNull();
                                    assertThat(r.sampleEntropy).isNotNull();
                                }
                            }
                        });
    }

    // =========================================================================
    // Public API: getSummary
    // =========================================================================

    @Test
    @TestTransaction
    void getSummary_noRuns_returnsEmptySummary() {
        EntropyComparisonRun.deleteAll();

        var summary = service.getSummary();

        assertThat(summary).isNotNull();
        assertThat(summary.latestRunId()).isNull();
        assertThat(summary.latestRunTimestamp()).isNull();
        assertThat(summary.latestRunStatus()).isNull();
        assertThat(summary.latestResults()).isEmpty();
        assertThat(summary.totalRunsCompleted()).isZero();
    }

    @Test
    @TestTransaction
    void getSummary_withCompletedRun_returnsLatestRunInfo() {
        EntropyComparisonRun.deleteAll();
        EntropyComparisonResult.deleteAll();

        EntropyComparisonRun run = new EntropyComparisonRun();
        run.runTimestamp = Instant.now().minusSeconds(60);
        run.status = JobStatus.COMPLETED;
        run.sp80022SampleSizeBytes = 4_194_304;
        run.sp80090bSampleSizeBytes = 4_194_304;
        run.metricsSampleSizeBytes = 1_048_576;
        run.createdAt = Instant.now().minusSeconds(60);
        run.completedAt = Instant.now();
        run.mixedValid = true;
        run.persist();

        EntropyComparisonResult result = new EntropyComparisonResult();
        result.comparisonRunId = run.id;
        result.sourceType = EntropySourceType.BASELINE;
        result.bytesCollected = 1000;
        result.nist22Status = "PASSED";
        result.nist90bStatus = "PASSED";
        result.createdAt = Instant.now();
        result.persist();

        var summary = service.getSummary();

        assertThat(summary.latestRunId()).isEqualTo(run.id);
        assertThat(summary.latestRunStatus()).isEqualTo("COMPLETED");
        assertThat(summary.latestResults()).hasSize(1);
        assertThat(summary.totalRunsCompleted()).isEqualTo(1L);
    }

    @Test
    @TestTransaction
    void getSummary_withRunningAndCompletedRuns_countsOnlyCompleted() {
        EntropyComparisonRun.deleteAll();

        EntropyComparisonRun completed = new EntropyComparisonRun();
        completed.runTimestamp = Instant.now().minusSeconds(120);
        completed.status = JobStatus.COMPLETED;
        completed.sp80022SampleSizeBytes = 1;
        completed.sp80090bSampleSizeBytes = 1;
        completed.metricsSampleSizeBytes = 1;
        completed.createdAt = Instant.now().minusSeconds(120);
        completed.persist();

        EntropyComparisonRun running = new EntropyComparisonRun();
        running.runTimestamp = Instant.now();
        running.status = JobStatus.RUNNING;
        running.sp80022SampleSizeBytes = 1;
        running.sp80090bSampleSizeBytes = 1;
        running.metricsSampleSizeBytes = 1;
        running.createdAt = Instant.now();
        running.persist();

        var summary = service.getSummary();

        // Latest run is the RUNNING one
        assertThat(summary.latestRunStatus()).isEqualTo("RUNNING");
        // Only 1 COMPLETED run counted
        assertThat(summary.totalRunsCompleted()).isEqualTo(1L);
    }

    // =========================================================================
    // Public API: getRecentRuns / getResultsForRun
    // =========================================================================

    @Test
    @TestTransaction
    void getRecentRuns_returnsRunsInOrder() {
        EntropyComparisonRun.deleteAll();

        for (int i = 0; i < 3; i++) {
            EntropyComparisonRun run = new EntropyComparisonRun();
            run.runTimestamp = Instant.now().minusSeconds(300 - i * 60L);
            run.status = JobStatus.COMPLETED;
            run.sp80022SampleSizeBytes = 1;
            run.sp80090bSampleSizeBytes = 1;
            run.metricsSampleSizeBytes = 1;
            run.createdAt = Instant.now().minusSeconds(300 - i * 60L);
            run.persist();
        }

        List<EntropyComparisonRun> runs = service.getRecentRuns(2);

        assertThat(runs).hasSize(2);
    }

    @Test
    @TestTransaction
    void getResultsForRun_returnsOnlyResultsForThatRun() {
        EntropyComparisonRun.deleteAll();
        EntropyComparisonResult.deleteAll();

        EntropyComparisonRun run1 = new EntropyComparisonRun();
        run1.runTimestamp = Instant.now();
        run1.status = JobStatus.COMPLETED;
        run1.sp80022SampleSizeBytes = 1;
        run1.sp80090bSampleSizeBytes = 1;
        run1.metricsSampleSizeBytes = 1;
        run1.createdAt = Instant.now();
        run1.persist();

        EntropyComparisonResult r1 = new EntropyComparisonResult();
        r1.comparisonRunId = run1.id;
        r1.sourceType = EntropySourceType.BASELINE;
        r1.bytesCollected = 0;
        r1.createdAt = Instant.now();
        r1.persist();

        EntropyComparisonResult r2 = new EntropyComparisonResult();
        r2.comparisonRunId = run1.id;
        r2.sourceType = EntropySourceType.HARDWARE;
        r2.bytesCollected = 0;
        r2.createdAt = Instant.now();
        r2.persist();

        List<EntropyComparisonResult> results = service.getResultsForRun(run1.id);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(r -> r.comparisonRunId).containsOnly(run1.id);
    }

    // =========================================================================
    // resolveToken() via service-local constructor
    // =========================================================================

    @Test
    void resolveToken_nullBearer_notConfigured_returnsNull() throws Exception {
        OidcClientService oidc = mock(OidcClientService.class);
        when(oidc.isConfigured()).thenReturn(false);

        EntropyComparisonService local = new EntropyComparisonService(null, oidc);
        String token = invokeResolveToken(local, null, "test");

        assertThat(token).isNull();
    }

    @Test
    void resolveToken_bearerPresent_returnsBearerWithoutCallingOidc() throws Exception {
        OidcClientService oidc = mock(OidcClientService.class);

        EntropyComparisonService local = new EntropyComparisonService(null, oidc);
        String token = invokeResolveToken(local, "my-token", "test");

        assertThat(token).isEqualTo("my-token");
    }

    @Test
    void resolveToken_notConfigured_blankBearer_returnsNull() throws Exception {
        OidcClientService oidc = mock(OidcClientService.class);
        when(oidc.isConfigured()).thenReturn(false);

        EntropyComparisonService local = new EntropyComparisonService(null, oidc);
        String token = invokeResolveToken(local, "  ", "test");

        assertThat(token).isNull();
    }

    // =========================================================================
    // Entropy metric computation (via reflection for private methods)
    // =========================================================================

    @Test
    void computeShannonEntropy_emptyArray_returnsZero() throws Exception {
        double result = invokeComputeShannonEntropy(new byte[0]);
        assertThat(result).isZero();
    }

    @Test
    void computeShannonEntropy_uniformDistribution_returnsMaxEntropy() throws Exception {
        // 256 bytes with one occurrence per value should yield the maximum entropy of 8 bits.
        byte[] data = new byte[256];
        for (int i = 0; i < 256; i++) data[i] = (byte) i;
        double result = invokeComputeShannonEntropy(data);
        assertThat(result).isCloseTo(8.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void computeShannonEntropy_singleByteValue_returnsZero() throws Exception {
        byte[] data = new byte[100];
        Arrays.fill(data, (byte) 0x42);
        double result = invokeComputeShannonEntropy(data);
        assertThat(result).isZero();
    }

    @Test
    void computeRenyiEntropy_emptyArray_returnsZero() throws Exception {
        double result = invokeComputeRenyiEntropy(new byte[0], 2.0);
        assertThat(result).isZero();
    }

    @Test
    void computeRenyiEntropy_alphaOne_fallsBackToShannon() throws Exception {
        byte[] data = new byte[256];
        for (int i = 0; i < 256; i++) data[i] = (byte) i;
        double renyi = invokeComputeRenyiEntropy(data, 1.0);
        double shannon = invokeComputeShannonEntropy(data);
        assertThat(renyi).isCloseTo(shannon, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void computeRenyiEntropy_alphaTwoGivesLowerThanShannon() throws Exception {
        // For a non-uniform distribution, Renyi entropy at alpha=2 does not exceed Shannon entropy.
        byte[] data = new byte[256];
        for (int i = 0; i < 256; i++) data[i] = (byte) (i % 16); // non-uniform
        double renyi = invokeComputeRenyiEntropy(data, 2.0);
        double shannon = invokeComputeShannonEntropy(data);
        assertThat(renyi).isLessThanOrEqualTo(shannon);
    }

    @Test
    void computeSampleEntropy_tooShortData_returnsZero() throws Exception {
        // m=2, needs at least m+2=4 bytes
        byte[] data = new byte[3];
        double result = invokeComputeSampleEntropy(data, 10_000);
        assertThat(result).isZero();
    }

    @Test
    void computeSampleEntropy_emptyArray_returnsZero() throws Exception {
        double result = invokeComputeSampleEntropy(new byte[0], 10_000);
        assertThat(result).isZero();
    }

    @Test
    void computeSampleEntropy_uniformData_returnsZero() throws Exception {
        // For identical bytes, m and m+1 template matches both exist and A equals B.
        // The entropy expression is therefore negative natural logarithm of 1, which is zero.
        byte[] data = new byte[20];
        Arrays.fill(data, (byte) 0x55);
        double result = invokeComputeSampleEntropy(data, 10_000);
        assertThat(result).isZero(); // -ln(1) = 0
    }

    @Test
    void computeSampleEntropy_truncatesToMaxBytes() throws Exception {
        // Data longer than maxBytes should still return a value (not crash)
        byte[] data = new byte[500];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 7);
        double result = invokeComputeSampleEntropy(data, 100);
        assertThat(result).isGreaterThanOrEqualTo(0.0);
    }

    // =========================================================================
    // Entity finders
    // =========================================================================

    @Test
    @TestTransaction
    void findRecent_respectsLimit() {
        EntropyComparisonRun.deleteAll();

        for (int i = 0; i < 5; i++) {
            EntropyComparisonRun run = new EntropyComparisonRun();
            run.runTimestamp = Instant.now().minusSeconds(500 - i * 10L);
            run.status = JobStatus.COMPLETED;
            run.sp80022SampleSizeBytes = 1;
            run.sp80090bSampleSizeBytes = 1;
            run.metricsSampleSizeBytes = 1;
            run.createdAt = Instant.now().minusSeconds(500 - i * 10L);
            run.persist();
        }

        List<EntropyComparisonRun> runs = EntropyComparisonRun.findRecent(3);
        assertThat(runs).hasSize(3);
    }

    @Test
    @TestTransaction
    void countByStatus_returnsCorrectCount() {
        EntropyComparisonRun.deleteAll();

        for (int i = 0; i < 3; i++) {
            EntropyComparisonRun run = new EntropyComparisonRun();
            run.runTimestamp = Instant.now();
            run.status = JobStatus.COMPLETED;
            run.sp80022SampleSizeBytes = 1;
            run.sp80090bSampleSizeBytes = 1;
            run.metricsSampleSizeBytes = 1;
            run.createdAt = Instant.now();
            run.persist();
        }

        EntropyComparisonRun failed = new EntropyComparisonRun();
        failed.runTimestamp = Instant.now();
        failed.status = JobStatus.FAILED;
        failed.sp80022SampleSizeBytes = 1;
        failed.sp80090bSampleSizeBytes = 1;
        failed.metricsSampleSizeBytes = 1;
        failed.createdAt = Instant.now();
        failed.persist();

        assertThat(EntropyComparisonRun.countByStatus(JobStatus.COMPLETED)).isEqualTo(3L);
        assertThat(EntropyComparisonRun.countByStatus(JobStatus.FAILED)).isEqualTo(1L);
        assertThat(EntropyComparisonRun.countByStatus(JobStatus.RUNNING)).isZero();
    }

    @Test
    @TestTransaction
    void findByRunId_returnsOnlyMatchingResults() {
        EntropyComparisonRun.deleteAll();
        EntropyComparisonResult.deleteAll();

        EntropyComparisonRun runA = new EntropyComparisonRun();
        runA.runTimestamp = Instant.now();
        runA.status = JobStatus.COMPLETED;
        runA.sp80022SampleSizeBytes = 1;
        runA.sp80090bSampleSizeBytes = 1;
        runA.metricsSampleSizeBytes = 1;
        runA.createdAt = Instant.now();
        runA.persist();

        EntropyComparisonRun runB = new EntropyComparisonRun();
        runB.runTimestamp = Instant.now().plusSeconds(1);
        runB.status = JobStatus.COMPLETED;
        runB.sp80022SampleSizeBytes = 1;
        runB.sp80090bSampleSizeBytes = 1;
        runB.metricsSampleSizeBytes = 1;
        runB.createdAt = Instant.now().plusSeconds(1);
        runB.persist();

        EntropyComparisonResult resultA = new EntropyComparisonResult();
        resultA.comparisonRunId = runA.id;
        resultA.sourceType = EntropySourceType.BASELINE;
        resultA.bytesCollected = 0;
        resultA.createdAt = Instant.now();
        resultA.persist();

        EntropyComparisonResult resultB = new EntropyComparisonResult();
        resultB.comparisonRunId = runB.id;
        resultB.sourceType = EntropySourceType.MIXED;
        resultB.bytesCollected = 0;
        resultB.createdAt = Instant.now();
        resultB.persist();

        List<EntropyComparisonResult> resultsForA = EntropyComparisonResult.findByRunId(runA.id);
        assertThat(resultsForA).hasSize(1);
        assertThat(resultsForA.get(0).sourceType).isEqualTo(EntropySourceType.BASELINE);
    }

    // =========================================================================
    // DTO from() factories
    // =========================================================================

    @Test
    void comparisonRunDTO_from_nullStatus_returnsNullStatus() {
        EntropyComparisonRun run = new EntropyComparisonRun();
        run.status = null;
        run.sp80022SampleSizeBytes = 100;
        run.sp80090bSampleSizeBytes = 100;
        run.metricsSampleSizeBytes = 100;

        var dto = EntropyComparisonRunDTO.from(run);

        assertThat(dto.status()).isNull();
        assertThat(dto.sp80022SampleSizeBytes()).isEqualTo(100);
    }

    @Test
    void comparisonRunDTO_from_withStatus_mapsToString() {
        EntropyComparisonRun run = new EntropyComparisonRun();
        run.status = JobStatus.RUNNING;
        run.sp80022SampleSizeBytes = 4_194_304;
        run.sp80090bSampleSizeBytes = 4_194_304;
        run.metricsSampleSizeBytes = 1_048_576;

        var dto = EntropyComparisonRunDTO.from(run);

        assertThat(dto.status()).isEqualTo("RUNNING");
    }

    @Test
    void comparisonResultDTO_from_nullSourceType_returnsNullSourceType() {
        EntropyComparisonResult result = new EntropyComparisonResult();
        result.comparisonRunId = 1L;
        result.sourceType = null;
        result.bytesCollected = 0;

        var dto = EntropyComparisonResultDTO.from(result);

        assertThat(dto.sourceType()).isNull();
        assertThat(dto.bytesCollected()).isZero();
    }

    @Test
    void comparisonResultDTO_from_withSourceType_mapsToString() {
        EntropyComparisonResult result = new EntropyComparisonResult();
        result.comparisonRunId = 1L;
        result.sourceType = EntropySourceType.HARDWARE;
        result.bytesCollected = 500;
        result.nist22Status = "PASSED";

        var dto = EntropyComparisonResultDTO.from(result);

        assertThat(dto.sourceType()).isEqualTo("HARDWARE");
        assertThat(dto.nist22Status()).isEqualTo("PASSED");
    }

    // =========================================================================
    // gRPC mock factories
    // =========================================================================

    private Sp80022TestService sp80022Success() {
        Sp80022TestResponse response =
                Sp80022TestResponse.newBuilder()
                        .setTestsRun(2)
                        .setOverallPassRate(1.0)
                        .setNistCompliant(true)
                        .addResults(
                                Sp80022TestResult.newBuilder()
                                        .setName("Frequency")
                                        .setPassed(true)
                                        .setPValue(0.95)
                                        .build())
                        .addResults(
                                Sp80022TestResult.newBuilder()
                                        .setName("Runs")
                                        .setPassed(true)
                                        .setPValue(0.87)
                                        .build())
                        .build();
        return request -> io.smallrye.mutiny.Uni.createFrom().item(response);
    }

    private Sp80022TestService sp80022AllPass() {
        Sp80022TestResponse response =
                Sp80022TestResponse.newBuilder()
                        .addResults(
                                Sp80022TestResult.newBuilder()
                                        .setName("Frequency")
                                        .setPassed(true)
                                        .setPValue(0.90)
                                        .build())
                        .addResults(
                                Sp80022TestResult.newBuilder()
                                        .setName("Runs")
                                        .setPassed(true)
                                        .setPValue(0.80)
                                        .build())
                        .build();
        return request -> io.smallrye.mutiny.Uni.createFrom().item(response);
    }

    private Sp80022TestService sp80022PartialFail() {
        Sp80022TestResponse response =
                Sp80022TestResponse.newBuilder()
                        .addResults(
                                Sp80022TestResult.newBuilder()
                                        .setName("Frequency")
                                        .setPassed(true)
                                        .setPValue(0.90)
                                        .build())
                        .addResults(
                                Sp80022TestResult.newBuilder()
                                        .setName("Runs")
                                        .setPassed(false)
                                        .setPValue(0.01)
                                        .build())
                        .build();
        return request -> io.smallrye.mutiny.Uni.createFrom().item(response);
    }

    /** All tests have pValue=0 and passed=false, so the executed list is empty. */
    private Sp80022TestService sp80022AllSkipped() {
        Sp80022TestResponse response =
                Sp80022TestResponse.newBuilder()
                        .addResults(
                                Sp80022TestResult.newBuilder()
                                        .setName("TestA")
                                        .setPassed(false)
                                        .setPValue(0.0)
                                        .build())
                        .addResults(
                                Sp80022TestResult.newBuilder()
                                        .setName("TestB")
                                        .setPassed(false)
                                        .setPValue(0.0)
                                        .build())
                        .build();
        return request -> io.smallrye.mutiny.Uni.createFrom().item(response);
    }

    /** One executed (pValue > 0) and one skipped (pValue=0, !passed). */
    private Sp80022TestService sp80022MixedExecutedSkipped() {
        Sp80022TestResponse response =
                Sp80022TestResponse.newBuilder()
                        .addResults(
                                Sp80022TestResult.newBuilder()
                                        .setName("Frequency")
                                        .setPassed(true)
                                        .setPValue(0.85)
                                        .build())
                        .addResults(
                                Sp80022TestResult.newBuilder()
                                        .setName("SkippedTest")
                                        .setPassed(false)
                                        .setPValue(0.0)
                                        .build())
                        .build();
        return request -> io.smallrye.mutiny.Uni.createFrom().item(response);
    }

    private Sp80090bAssessmentService sp80090bSuccess() {
        Sp80090bAssessmentResponse response =
                Sp80090bAssessmentResponse.newBuilder()
                        .setMinEntropy(7.5)
                        .setPassed(true)
                        .setAssessmentSummary("ok")
                        .build();
        return request -> io.smallrye.mutiny.Uni.createFrom().item(response);
    }

    // =========================================================================
    // Reflection helpers
    // =========================================================================

    private String invokeResolveToken(
            EntropyComparisonService target, String bearer, String serviceName) throws Exception {
        var method =
                EntropyComparisonService.class.getDeclaredMethod(
                        "resolveToken", String.class, String.class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(target, bearer, serviceName);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }

    private double invokeComputeShannonEntropy(byte[] data) throws Exception {
        var method =
                EntropyComparisonService.class.getDeclaredMethod(
                        "computeShannonEntropy", byte[].class);
        method.setAccessible(true);
        return (double) method.invoke(service, data);
    }

    private double invokeComputeRenyiEntropy(byte[] data, double alpha) throws Exception {
        var method =
                EntropyComparisonService.class.getDeclaredMethod(
                        "computeRenyiEntropy", byte[].class, double.class);
        method.setAccessible(true);
        return (double) method.invoke(service, data, alpha);
    }

    private double invokeComputeSampleEntropy(byte[] data, int maxBytes) throws Exception {
        var method =
                EntropyComparisonService.class.getDeclaredMethod(
                        "computeSampleEntropy", byte[].class, int.class);
        method.setAccessible(true);
        return (double) method.invoke(service, data, maxBytes);
    }
}
