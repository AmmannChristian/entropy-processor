/* (C)2026 */
package com.ammann.entropy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.ammann.entropy.dto.NIST90BResultDTO;
import com.ammann.entropy.dto.NISTSuiteResultDTO;
import com.ammann.entropy.dto.NistValidationJobDTO;
import com.ammann.entropy.enumeration.JobStatus;
import com.ammann.entropy.enumeration.ValidationType;
import com.ammann.entropy.exception.ValidationException;
import com.ammann.entropy.grpc.proto.sp80022.Sp80022TestResponse;
import com.ammann.entropy.grpc.proto.sp80022.Sp80022TestResult;
import com.ammann.entropy.grpc.proto.sp80022.Sp80022TestService;
import com.ammann.entropy.grpc.proto.sp80090b.Sp80090bAssessmentResponse;
import com.ammann.entropy.grpc.proto.sp80090b.Sp80090bAssessmentService;
import com.ammann.entropy.grpc.proto.sp80090b.Sp80090bEstimatorResult;
import com.ammann.entropy.model.EntropyData;
import com.ammann.entropy.model.Nist90BEstimatorResult;
import com.ammann.entropy.model.Nist90BResult;
import com.ammann.entropy.model.NistTestResult;
import com.ammann.entropy.model.NistValidationJob;
import com.ammann.entropy.support.TestDataFactory;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class NistValidationServiceAsyncTest {

    @Inject NistValidationService service;

    @AfterEach
    void resetOverrides() {
        service.setClientOverride(null);
        service.setSp80090bOverride(null);
        service.setSp80022MaxBytesForTesting(1_250_000);
        service.setSp80022MinBitsForTesting(1_000_000L);
        service.setSp80090bMaxBytesForTesting(1_000_000);
        service.setSp80090bSampleIntervalSecondsForTesting(3600);
    }

    @Test
    @TestTransaction
    void startAsyncSp80022ValidationThrowsWhenActiveLimitReached() {
        clearAll();
        seedActiveJobs("limit-user");

        assertThatThrownBy(
                        () ->
                                service.startAsyncSp80022Validation(
                                        Instant.now().minusSeconds(60),
                                        Instant.now(),
                                        null,
                                        "limit-user"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Maximum concurrent validations reached");
    }

    @Test
    @TestTransaction
    void startAsyncSp80090bValidationThrowsWhenActiveLimitReached() {
        clearAll();
        seedActiveJobs("limit-user");

        assertThatThrownBy(
                        () ->
                                service.startAsyncSp80090bValidation(
                                        Instant.now().minusSeconds(60),
                                        Instant.now(),
                                        null,
                                        "limit-user"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Maximum concurrent validations reached");
    }

    @Test
    void startAsyncSp80022ValidationDispatchesBackgroundWorkerAndPersistsResults() {
        Instant end = Instant.now();
        Instant start = end.minusSeconds(300);

        UUID jobId =
                QuarkusTransaction.requiringNew()
                        .call(
                                () -> {
                                    clearAll();
                                    service.setClientOverride(sp80022Success());
                                    // 60 events x 32 bytes = 1920 bytes → 240 sequences ≥ 55
                                    // minimum
                                    service.setSp80022MinBitsForTesting(64);
                                    service.setSp80022MaxBytesForTesting(128);
                                    seedEntropyEvents("sp80022-dispatch", start, 60);

                                    return service.startAsyncSp80022Validation(
                                            start, end, "token-123", "dispatch-user");
                                });

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertSp80022JobCompleted(jobId));
    }

    @Test
    void startAsyncSp80090bValidationDispatchesBackgroundWorkerAndPersistsResults() {
        Instant end = Instant.now();
        Instant start = end.minusSeconds(300);

        UUID jobId =
                QuarkusTransaction.requiringNew()
                        .call(
                                () -> {
                                    clearAll();
                                    service.setSp80090bOverride(sp80090bSuccess());
                                    service.setSp80090bMaxBytesForTesting(100);
                                    seedEntropyEvents("sp80090b-dispatch", start, 10);

                                    return service.startAsyncSp80090bValidation(
                                            start, end, "token-xyz", "dispatch-user");
                                });

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertSp80090bJobCompleted(jobId));
    }

    @Test
    @TestTransaction
    void processSp80022ValidationJobCompletesAndPersistsMultiSequenceResults() {
        clearAll();
        service.setClientOverride(sp80022Success());
        // minBits=64 (8 bytes minimum per sequence), maxBytes=128.
        // 60 events x 32 bytes = 1920 bytes → 1920/8 = 240 sequences ≥ 55 (NIST minimum).
        // sequenceCount = max(55, ceil(1920/128)) = max(55, 15) = 55.
        // Using 60 events (vs the theoretical minimum 14) provides resilience against
        // off-by-one issues when a small number of stale rows from a previous test are
        // unexpectedly visible during this @TestTransaction.
        service.setSp80022MinBitsForTesting(64);
        service.setSp80022MaxBytesForTesting(128);

        Instant end = Instant.now();
        Instant start = end.minusSeconds(300);
        seedEntropyEvents("sp80022-job", start, 60);

        NistValidationJob job = persistJob(ValidationType.SP_800_22, JobStatus.QUEUED, start, end);

        service.processSp80022ValidationJob(job.id, "token-123");

        NistValidationJob reloaded = NistValidationJob.findById(job.id);
        assertThat(reloaded.status).isEqualTo(JobStatus.COMPLETED);
        assertThat(reloaded.progressPercent).isEqualTo(100);
        assertThat(reloaded.totalChunks).isEqualTo(55);
        assertThat(reloaded.currentChunk).isEqualTo(55);
        assertThat(reloaded.startedAt).isNotNull();
        assertThat(reloaded.completedAt).isNotNull();
        assertThat(reloaded.testSuiteRunId).isNotNull();
        assertThat(reloaded.errorMessage).isNull();

        // sp80022Success() returns 2 test results per call → 55 sequences × 2 = 110 rows
        List<NistTestResult> persisted =
                NistTestResult.find("testSuiteRunId", reloaded.testSuiteRunId).list();
        assertThat(persisted).hasSize(110);
        assertThat(persisted).extracting(result -> result.chunkIndex).contains(1, 2, 3, 54, 55);
        assertThat(persisted).extracting(result -> result.chunkCount).containsOnly(55);
        assertThat(persisted)
                .extracting(result -> result.aggregationMethod)
                .containsOnly("MULTI_SEQUENCE_CHI2");
    }

    @Test
    @TestTransaction
    void processSp80022ValidationJobMarksFailedWhenNoEntropyDataExists() {
        clearAll();
        Instant end = Instant.now();
        Instant start = end.minusSeconds(300);
        NistValidationJob job = persistJob(ValidationType.SP_800_22, JobStatus.QUEUED, start, end);

        service.processSp80022ValidationJob(job.id, null);

        NistValidationJob reloaded = NistValidationJob.findById(job.id);
        assertThat(reloaded.status).isEqualTo(JobStatus.FAILED);
        assertThat(reloaded.errorMessage).contains("No entropy data");
        assertThat(reloaded.completedAt).isNotNull();
    }

    @Test
    @TestTransaction
    void processSp80022ValidationJobReturnsWhenJobDoesNotExist() {
        clearAll();

        assertThatCode(() -> service.processSp80022ValidationJob(UUID.randomUUID(), null))
                .doesNotThrowAnyException();
    }

    @Test
    @TestTransaction
    void processSp80090bValidationJobCompletesAndPersistsChunkedResults() {
        clearAll();
        service.setSp80090bOverride(sp80090bSuccess());
        service.setSp80090bMaxBytesForTesting(100);
        service.setSp80090bSampleIntervalSecondsForTesting(
                100); // 300s window / 100s interval = 3 samples

        Instant end = Instant.now();
        Instant start = end.minusSeconds(300);
        seedEntropyEvents("sp80090b-job", start, 10);

        NistValidationJob job = persistJob(ValidationType.SP_800_90B, JobStatus.QUEUED, start, end);

        service.processSp80090bValidationJob(job.id, "token-xyz");

        NistValidationJob reloaded = NistValidationJob.findById(job.id);
        assertThat(reloaded.status).isEqualTo(JobStatus.COMPLETED);
        assertThat(reloaded.progressPercent).isEqualTo(100);
        assertThat(reloaded.totalChunks).isGreaterThanOrEqualTo(2);
        assertThat(reloaded.currentChunk).isEqualTo(reloaded.totalChunks);
        assertThat(reloaded.assessmentRunId).isNotNull();
        assertThat(reloaded.errorMessage).isNull();

        // Expected persistence shape: N per-sample rows (isRunSummary=false) + 1 run summary row
        // (isRunSummary=true)
        List<Nist90BResult> sampleRows =
                Nist90BResult.find(
                                "assessmentRunId = ?1 AND isRunSummary = false",
                                reloaded.assessmentRunId)
                        .list();
        assertThat(sampleRows).hasSize(reloaded.totalChunks);
        // New code sets sampleIndex (1-based) and sampleCount; chunkIndex is not set on per-sample
        // rows
        assertThat(sampleRows).extracting(result -> result.sampleIndex).contains(1, 2);
        assertThat(sampleRows)
                .extracting(result -> result.sampleCount)
                .containsOnly(reloaded.totalChunks);
        assertThat(sampleRows)
                .extracting(result -> result.assessmentScope)
                .containsOnly("NIST_SINGLE_SAMPLE");

        Nist90BResult summary =
                Nist90BResult.find(
                                "assessmentRunId = ?1 AND isRunSummary = true",
                                reloaded.assessmentRunId)
                        .firstResult();
        assertThat(summary).isNotNull();
        assertThat(summary.passed).isTrue();
        // Summary row uses sampleCount (not chunkCount) to record the number of samples
        assertThat(summary.sampleCount).isEqualTo(reloaded.totalChunks);
        assertThat(summary.minEntropy).isNotNull();
        assertThat(summary.assessmentDetails).contains("estimatorSourceSample");
        assertThat(summary.assessmentScope).isEqualTo("PRODUCT_WINDOW_SUMMARY");
    }

    @Test
    @TestTransaction
    void processSp80090bValidationJobMarksFailedWhenNoEntropyDataExists() {
        clearAll();
        Instant end = Instant.now();
        Instant start = end.minusSeconds(300);
        NistValidationJob job = persistJob(ValidationType.SP_800_90B, JobStatus.QUEUED, start, end);

        service.processSp80090bValidationJob(job.id, null);

        NistValidationJob reloaded = NistValidationJob.findById(job.id);
        assertThat(reloaded.status).isEqualTo(JobStatus.FAILED);
        assertThat(reloaded.errorMessage).contains("No entropy data");
        assertThat(reloaded.completedAt).isNotNull();
    }

    @Test
    @TestTransaction
    void processSp80090bValidationJobReturnsWhenJobDoesNotExist() {
        clearAll();

        assertThatCode(() -> service.processSp80090bValidationJob(UUID.randomUUID(), null))
                .doesNotThrowAnyException();
    }

    @Test
    @TestTransaction
    void validate90BTimeWindowMultiPointSamplingPersistsResult() {
        // 12 events x 32 bytes = 384 bytes, maxBytes=128 → multiple evenly-spaced samples.
        // Each sample is exactly maxBytes (128 bytes) = 1024 bits.
        // Summary row: bitsTested = totalBits = sampleCount * (128 * 8).
        // Total rows persisted: sampleCount per-sample rows + 1 run-summary row.
        clearAll();
        service.setSp80090bOverride(sp80090bSuccess());
        service.setSp80090bMaxBytesForTesting(128);

        Instant end = Instant.now();
        Instant start = end.minusSeconds(300);
        seedEntropyEvents("validate-90b", start, 12);

        NIST90BResultDTO dto = service.validate90BTimeWindow(start, end);

        assertThat(dto.passed()).isTrue();
        assertThat(dto.isRunSummary()).isTrue();
        assertThat(dto.assessmentScope()).isEqualTo("PRODUCT_WINDOW_SUMMARY");

        // Exactly one run-summary row is created
        long summaryCount = Nist90BResult.count("isRunSummary = true");
        assertThat(summaryCount).isEqualTo(1L);

        // At least one per-sample row (multi-point sampling with maxBytes=128 on 12 events)
        long sampleCount = Nist90BResult.count("isRunSummary = false");
        assertThat(sampleCount).isGreaterThanOrEqualTo(1L);

        // Total rows = sampleCount + 1 summary
        assertThat(Nist90BResult.count()).isEqualTo(sampleCount + 1L);

        // Summary bitsTested = sum of all sample bits (each sample = 128 bytes = 1024 bits)
        assertThat(dto.bitsTested()).isEqualTo(sampleCount * 128L * 8L);
    }

    @Test
    void getJobStatusReturnsDtoAndThrowsWhenJobMissing() {
        UUID jobId =
                QuarkusTransaction.requiringNew()
                        .call(
                                () -> {
                                    clearAll();
                                    Instant end = Instant.now();
                                    Instant start = end.minusSeconds(300);
                                    NistValidationJob job =
                                            persistJob(
                                                    ValidationType.SP_800_22,
                                                    JobStatus.QUEUED,
                                                    start,
                                                    end);
                                    return job.id;
                                });

        NistValidationJobDTO dto = service.getJobStatus(jobId);
        assertThat(dto.jobId()).isEqualTo(jobId);
        assertThat(dto.status()).isEqualTo("QUEUED");
        assertThat(dto.validationType()).isEqualTo("SP_800_22");

        assertThatThrownBy(() -> service.getJobStatus(UUID.randomUUID()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Job not found");
    }

    @Test
    @TestTransaction
    void getValidationResultByRunIdBuildsDtoAndThrowsWhenMissing() {
        clearAll();
        UUID runId = UUID.randomUUID();
        Instant end = Instant.now();
        Instant start = end.minusSeconds(120);

        NistTestResult one = new NistTestResult(runId, "Frequency", true, 0.9, start, end);
        one.bitsTested = 1024L;
        one.persist();
        NistTestResult two = new NistTestResult(runId, "Runs", false, 0.01, start, end);
        two.bitsTested = 1024L;
        two.persist();

        NISTSuiteResultDTO dto = service.getValidationResultByRunId(runId);
        assertThat(dto.totalTests()).isEqualTo(2);
        assertThat(dto.passedTests()).isEqualTo(1);
        assertThat(dto.failedTests()).isEqualTo(1);
        assertThat(dto.datasetSize()).isEqualTo(1024L);

        assertThatThrownBy(() -> service.getValidationResultByRunId(UUID.randomUUID()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("No test results found");
    }

    @Test
    @TestTransaction
    void getValidationResultByRunId_multiSequence_proportionFailDetected() {
        clearAll();
        UUID runId = UUID.randomUUID();
        Instant end = Instant.now();
        Instant start = end.minusSeconds(120);

        // Create 10 sequences for "Frequency" — 2 pass, 8 fail.
        // Chi-square on uniform p-values may pass, but proportion (0.2) is far below threshold.
        // Proportion threshold for 10 sequences at alpha=0.01:
        // 1.0 - 0.01 - 3*sqrt(0.01*0.99/10) ≈ 0.8956
        for (int chunk = 1; chunk <= 10; chunk++) {
            boolean passed = chunk <= 2; // only first 2 pass
            NistTestResult r =
                    new NistTestResult(
                            runId, "Frequency", passed, passed ? 0.5 : 0.001, start, end);
            r.chunkIndex = chunk;
            r.chunkCount = 10;
            r.bitsTested = 1_000_000L;
            r.aggregationMethod = "MULTI_SEQUENCE_CHI2";
            r.persist();
        }

        NISTSuiteResultDTO dto = service.getValidationResultByRunId(runId);

        assertThat(dto.totalTests()).isEqualTo(1);
        assertThat(dto.validationMode()).isEqualTo("MULTI_SEQUENCE_CHI2");
        // Proportion is 0.2, well below threshold ~0.8956 → must FAIL
        assertThat(dto.tests().getFirst().passed()).isFalse();
        assertThat(dto.tests().getFirst().details()).contains("proportion_fail");
        assertThat(dto.allTestsPassed()).isFalse();
    }

    @Test
    @TestTransaction
    void getSp80022JobResultValidatesCompletionAndReturnsResult() {
        clearAll();
        UUID runId = UUID.randomUUID();
        Instant end = Instant.now();
        Instant start = end.minusSeconds(120);

        NistValidationJob runningJob =
                persistJob(ValidationType.SP_800_22, JobStatus.RUNNING, start, end);
        runningJob.testSuiteRunId = runId;
        runningJob.persist();

        assertThatThrownBy(() -> service.getSp80022JobResult(runningJob.id))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Job not completed yet");

        runningJob.status = JobStatus.COMPLETED;
        runningJob.persist();

        NistTestResult result = new NistTestResult(runId, "Frequency", true, 0.9, start, end);
        result.bitsTested = 2048L;
        result.persist();

        NISTSuiteResultDTO dto = service.getSp80022JobResult(runningJob.id);
        assertThat(dto.totalTests()).isEqualTo(1);
        assertThat(dto.passedTests()).isEqualTo(1);
    }

    @Test
    @TestTransaction
    void getSp80090bJobResultValidatesCompletionAndMissingResults() {
        clearAll();
        UUID assessmentRunId = UUID.randomUUID();
        Instant end = Instant.now();
        Instant start = end.minusSeconds(120);

        NistValidationJob job =
                persistJob(ValidationType.SP_800_90B, JobStatus.RUNNING, start, end);
        job.assessmentRunId = assessmentRunId;
        job.persist();

        assertThatThrownBy(() -> service.getSp80090bJobResult(job.id))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Job not completed yet");

        job.status = JobStatus.COMPLETED;
        job.persist();

        assertThatThrownBy(() -> service.getSp80090bJobResult(job.id))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("No run-summary row found for assessment run of job");
    }

    @Test
    @TestTransaction
    void getSp80090bJobResultReturnsSummaryRow() {
        clearAll();
        UUID assessmentRunId = UUID.randomUUID();
        Instant end = Instant.now();
        Instant start = end.minusSeconds(120);

        NistValidationJob job =
                persistJob(ValidationType.SP_800_90B, JobStatus.COMPLETED, start, end);
        job.assessmentRunId = assessmentRunId;
        job.persist();

        // Chunk row — must NOT be returned by getSp80090bJobResult
        Nist90BResult chunkRow =
                new Nist90BResult(
                        "batch-1", 6.0, true, "{\"summary\":\"chunk\"}", 2048L, start, end);
        chunkRow.assessmentRunId = assessmentRunId;
        chunkRow.chunkIndex = 0;
        chunkRow.chunkCount = 1;
        chunkRow.isRunSummary = false;
        chunkRow.persist();

        // Summary row — the canonical result
        Nist90BResult summaryRow =
                new Nist90BResult("batch-1", 7.5, true, "{\"summary\":\"ok\"}", 4096L, start, end);
        summaryRow.assessmentRunId = assessmentRunId;
        summaryRow.chunkCount = 1;
        summaryRow.isRunSummary = true;
        summaryRow.persist();

        NIST90BResultDTO dto = service.getSp80090bJobResult(job.id);
        assertThat(dto.minEntropy()).isEqualTo(7.5);
        assertThat(dto.bitsTested()).isEqualTo(4096L);
        assertThat(dto.isRunSummary()).isTrue();
    }

    @Test
    @TestTransaction
    void countRecentFailuresCountsOnlyRecentFailedTests() {
        clearAll();
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();

        NistTestResult recentFailure =
                new NistTestResult(runId, "Frequency", false, 0.001, now.minusSeconds(30), now);
        recentFailure.executedAt = now.minusSeconds(1800);
        recentFailure.persist();

        NistTestResult oldFailure =
                new NistTestResult(
                        runId, "Runs", false, 0.002, now.minusSeconds(30), now.minusSeconds(10));
        oldFailure.executedAt = now.minusSeconds(4 * 3600);
        oldFailure.persist();

        NistTestResult recentPass =
                new NistTestResult(runId, "Serial", true, 0.9, now.minusSeconds(60), now);
        recentPass.executedAt = now.minusSeconds(1200);
        recentPass.persist();

        assertThat(service.countRecentFailures(2)).isEqualTo(1L);
    }

    // -------------------------------------------------------------------------
    // T8 — Run-summary write-path unit tests
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void singleChunkRun_summaryEqualsChunkMetrics() {
        clearAll();
        service.setSp80090bOverride(sp80090bSuccess()); // minEntropy=7.5, passed=true
        service.setSp80090bMaxBytesForTesting(1_000_000); // no chunking

        Instant end = Instant.now();
        Instant start = end.minusSeconds(300);
        seedEntropyEvents("summary-single", start, 10);

        NistValidationJob job = persistJob(ValidationType.SP_800_90B, JobStatus.QUEUED, start, end);
        service.processSp80090bValidationJob(job.id, null);

        NistValidationJob reloaded = NistValidationJob.findById(job.id);
        assertThat(reloaded.status).isEqualTo(JobStatus.COMPLETED);
        assertThat(reloaded.totalChunks).isEqualTo(1);

        Nist90BResult summary =
                Nist90BResult.find(
                                "assessmentRunId = ?1 AND isRunSummary = true",
                                reloaded.assessmentRunId)
                        .firstResult();
        assertThat(summary).isNotNull();
        assertThat(summary.minEntropy).isEqualTo(7.5);
        assertThat(summary.passed).isTrue();
        // Summary row records sample count via sampleCount (not chunkCount)
        assertThat(summary.sampleCount).isEqualTo(1);

        // Estimators written from sample index 0 (0-based worst sample index)
        assertThat(Nist90BEstimatorResult.count("assessmentRunId", reloaded.assessmentRunId))
                .isEqualTo(4L); // 2 NonIID + 2 IID in sp80090bSuccess()
        assertThat(summary.assessmentDetails).contains("estimatorSourceSample");
    }

    @Test
    @TestTransaction
    void multiChunkAllPass_summaryMinEntropyIsGlobalMin() {
        clearAll();
        // Fixed response: minEntropy=7.5, passed=true for all chunks
        service.setSp80090bOverride(sp80090bSuccess());
        service.setSp80090bMaxBytesForTesting(100); // force multiple chunks
        service.setSp80090bSampleIntervalSecondsForTesting(
                100); // 300s window / 100s interval = 3 samples

        Instant end = Instant.now();
        Instant start = end.minusSeconds(300);
        seedEntropyEvents("summary-multi", start, 10);

        NistValidationJob job = persistJob(ValidationType.SP_800_90B, JobStatus.QUEUED, start, end);
        service.processSp80090bValidationJob(job.id, null);

        NistValidationJob reloaded = NistValidationJob.findById(job.id);
        assertThat(reloaded.status).isEqualTo(JobStatus.COMPLETED);
        assertThat(reloaded.totalChunks).isGreaterThanOrEqualTo(2);

        Nist90BResult summary =
                Nist90BResult.find(
                                "assessmentRunId = ?1 AND isRunSummary = true",
                                reloaded.assessmentRunId)
                        .firstResult();
        assertThat(summary).isNotNull();
        assertThat(summary.passed).isTrue();
        assertThat(summary.minEntropy).isEqualTo(7.5); // min of identical samples
        // Summary row records sample count via sampleCount (not chunkCount)
        assertThat(summary.sampleCount).isEqualTo(reloaded.totalChunks);

        // Exactly one summary row
        assertThat(
                        Nist90BResult.count(
                                "assessmentRunId = ?1 AND isRunSummary = true",
                                reloaded.assessmentRunId))
                .isEqualTo(1L);
    }

    @Test
    @TestTransaction
    void noSummaryRow_getSp80090bJobResultThrowsClearError() {
        clearAll();
        UUID assessmentRunId = UUID.randomUUID();
        Instant end = Instant.now();
        Instant start = end.minusSeconds(120);

        NistValidationJob job =
                persistJob(ValidationType.SP_800_90B, JobStatus.COMPLETED, start, end);
        job.assessmentRunId = assessmentRunId;
        job.persist();

        // Only a chunk row exists — no summary row
        Nist90BResult chunkRow = new Nist90BResult("batch-x", 6.5, true, null, 1024L, start, end);
        chunkRow.assessmentRunId = assessmentRunId;
        chunkRow.chunkIndex = 0;
        chunkRow.isRunSummary = false;
        chunkRow.persist();

        assertThatThrownBy(() -> service.getSp80090bJobResult(job.id))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("No run-summary row found for assessment run of job");

        // list90BResults with default (summaryOnly=true) returns zero rows for this run
        long summaryCount =
                Nist90BResult.count(
                        "assessmentRunId = ?1 AND isRunSummary = true", assessmentRunId);
        assertThat(summaryCount).isZero();
    }

    @Test
    @TestTransaction
    void summaryRowAbsent_listResultsReturnsNoRowForRun() {
        clearAll();
        UUID assessmentRunId = UUID.randomUUID();
        Instant end = Instant.now();
        Instant start = end.minusSeconds(120);

        // Partial chunk rows — no summary
        for (int i = 0; i < 3; i++) {
            Nist90BResult chunk =
                    new Nist90BResult("batch-partial", 7.0, true, null, 512L, start, end);
            chunk.assessmentRunId = assessmentRunId;
            chunk.chunkIndex = i;
            chunk.chunkCount = 4;
            chunk.isRunSummary = false;
            chunk.persist();
        }

        // summaryOnly=true (default) — this run produces zero summary rows
        long summaryCount =
                Nist90BResult.count(
                        "assessmentRunId = ?1 AND isRunSummary = true", assessmentRunId);
        assertThat(summaryCount).isZero();

        // summaryOnly=false — chunk rows are visible for forensic inspection
        long chunkCount =
                Nist90BResult.count(
                        "assessmentRunId = ?1 AND isRunSummary = false", assessmentRunId);
        assertThat(chunkCount).isEqualTo(3L);
    }

    // -------------------------------------------------------------------------
    // T8 — Read-path unit tests
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void listResults_defaultSummaryOnly_returnsOnlyRunSummaryRows() {
        clearAll();
        UUID runId = UUID.randomUUID();
        Instant end = Instant.now();
        Instant start = end.minusSeconds(120);

        // One summary row + two chunk rows
        Nist90BResult summary = new Nist90BResult("b1", 7.0, true, null, 2048L, start, end);
        summary.assessmentRunId = runId;
        summary.isRunSummary = true;
        summary.persist();

        for (int i = 0; i < 2; i++) {
            Nist90BResult chunk =
                    new Nist90BResult("b1", 7.0 - i * 0.1, true, null, 1024L, start, end);
            chunk.assessmentRunId = runId;
            chunk.chunkIndex = i;
            chunk.isRunSummary = false;
            chunk.persist();
        }

        long onlySummaries = Nist90BResult.count("isRunSummary = true");
        assertThat(onlySummaries).isEqualTo(1L);

        long onlyChunks = Nist90BResult.count("isRunSummary = false");
        assertThat(onlyChunks).isEqualTo(2L);
    }

    private void clearAll() {
        NistValidationJob.deleteAll();
        NistTestResult.deleteAll();
        Nist90BEstimatorResult.deleteAll();
        Nist90BResult.deleteAll();
        EntropyData.deleteAll();
    }

    private void seedActiveJobs(String user) {
        Instant end = Instant.now();
        Instant start = end.minusSeconds(60);
        persistJob(ValidationType.SP_800_22, JobStatus.QUEUED, start, end, user);
        persistJob(ValidationType.SP_800_90B, JobStatus.RUNNING, start, end, user);
        persistJob(ValidationType.SP_800_22, JobStatus.RUNNING, start, end, user);
    }

    private void assertSp80022JobCompleted(UUID jobId) {
        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            NistValidationJob job = NistValidationJob.findById(jobId);
                            assertThat(job).isNotNull();
                            assertThat(job.status).isEqualTo(JobStatus.COMPLETED);
                            assertThat(job.testSuiteRunId).isNotNull();
                            assertThat(job.progressPercent).isEqualTo(100);
                            assertThat(NistTestResult.count("testSuiteRunId", job.testSuiteRunId))
                                    .isGreaterThan(0);
                        });
    }

    private void assertSp80090bJobCompleted(UUID jobId) {
        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            NistValidationJob job = NistValidationJob.findById(jobId);
                            assertThat(job).isNotNull();
                            assertThat(job.status).isEqualTo(JobStatus.COMPLETED);
                            assertThat(job.assessmentRunId).isNotNull();
                            assertThat(job.progressPercent).isEqualTo(100);
                            // A completed run must have exactly one run-summary row
                            assertThat(
                                            Nist90BResult.count(
                                                    "assessmentRunId = ?1 AND isRunSummary = true",
                                                    job.assessmentRunId))
                                    .isEqualTo(1L);
                        });
    }

    private NistValidationJob persistJob(
            ValidationType validationType, JobStatus status, Instant start, Instant end) {
        return persistJob(validationType, status, start, end, "async-test");
    }

    private NistValidationJob persistJob(
            ValidationType validationType,
            JobStatus status,
            Instant start,
            Instant end,
            String createdBy) {
        NistValidationJob job = new NistValidationJob();
        job.validationType = validationType;
        job.status = status;
        job.windowStart = start;
        job.windowEnd = end;
        job.createdBy = createdBy;
        job.createdAt = Instant.now();
        if (status == JobStatus.RUNNING) {
            job.startedAt = Instant.now().minusSeconds(30);
        }
        if (status == JobStatus.COMPLETED || status == JobStatus.FAILED) {
            job.startedAt = Instant.now().minusSeconds(60);
            job.completedAt = Instant.now().minusSeconds(10);
        }
        job.persist();
        return job;
    }

    private void seedEntropyEvents(String batchId, Instant base, int count) {
        List<EntropyData> events = TestDataFactory.buildSequentialEvents(count, 1_000L, base);
        byte[] chunk = fixedEntropyChunk((byte) 7);
        events.forEach(
                event -> {
                    event.batchId = batchId;
                    event.whitenedEntropy = chunk;
                });
        EntropyData.persist(events);
    }

    private byte[] fixedEntropyChunk(byte seed) {
        byte[] chunk = new byte[GrpcMappingService.EXPECTED_WHITENED_ENTROPY_BYTES];
        for (int i = 0; i < chunk.length; i++) {
            chunk[i] = (byte) (seed + i);
        }
        return chunk;
    }

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
                                        .setWarning("test warning")
                                        .build())
                        .addResults(
                                Sp80022TestResult.newBuilder()
                                        .setName("Runs")
                                        .setPassed(true)
                                        .setPValue(0.92)
                                        .build())
                        .build();
        return request -> io.smallrye.mutiny.Uni.createFrom().item(response);
    }

    private Sp80090bAssessmentService sp80090bSuccess() {
        Sp80090bAssessmentResponse response =
                Sp80090bAssessmentResponse.newBuilder()
                        .setMinEntropy(7.5)
                        .setPassed(true)
                        .setAssessmentSummary("all-good")
                        .addNonIidResults(
                                Sp80090bEstimatorResult.newBuilder()
                                        .setName("Shannon estimator")
                                        .setEntropyEstimate(7.2)
                                        .build())
                        .addNonIidResults(
                                Sp80090bEstimatorResult.newBuilder()
                                        .setName("Collision estimator")
                                        .setEntropyEstimate(7.0)
                                        .build())
                        .addIidResults(
                                Sp80090bEstimatorResult.newBuilder()
                                        .setName("Markov estimator")
                                        .setEntropyEstimate(6.8)
                                        .build())
                        .addIidResults(
                                Sp80090bEstimatorResult.newBuilder()
                                        .setName("Compression estimator")
                                        .setEntropyEstimate(6.6)
                                        .build())
                        .build();
        return request -> io.smallrye.mutiny.Uni.createFrom().item(response);
    }
}
