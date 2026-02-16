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
                                    service.setSp80022MinBitsForTesting(64);
                                    service.setSp80022MaxBytesForTesting(128);
                                    seedEntropyEvents("sp80022-dispatch", start, 12);

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
    void processSp80022ValidationJobCompletesAndPersistsChunkedResults() {
        clearAll();
        service.setClientOverride(sp80022Success());
        service.setSp80022MinBitsForTesting(64);
        service.setSp80022MaxBytesForTesting(128);

        Instant end = Instant.now();
        Instant start = end.minusSeconds(300);
        seedEntropyEvents("sp80022-job", start, 12);

        NistValidationJob job = persistJob(ValidationType.SP_800_22, JobStatus.QUEUED, start, end);

        service.processSp80022ValidationJob(job.id, "token-123");

        NistValidationJob reloaded = NistValidationJob.findById(job.id);
        assertThat(reloaded.status).isEqualTo(JobStatus.COMPLETED);
        assertThat(reloaded.progressPercent).isEqualTo(100);
        assertThat(reloaded.totalChunks).isEqualTo(3);
        assertThat(reloaded.currentChunk).isEqualTo(3);
        assertThat(reloaded.startedAt).isNotNull();
        assertThat(reloaded.completedAt).isNotNull();
        assertThat(reloaded.testSuiteRunId).isNotNull();
        assertThat(reloaded.errorMessage).isNull();

        List<NistTestResult> persisted =
                NistTestResult.find("testSuiteRunId", reloaded.testSuiteRunId).list();
        assertThat(persisted).hasSize(6);
        assertThat(persisted).extracting(result -> result.chunkIndex).contains(1, 2, 3);
        assertThat(persisted).extracting(result -> result.chunkCount).containsOnly(3);
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

        List<Nist90BResult> persisted =
                Nist90BResult.list("assessmentRunId", reloaded.assessmentRunId);
        assertThat(persisted).hasSize(reloaded.totalChunks);
        assertThat(persisted).extracting(result -> result.chunkIndex).contains(0, 1);
        assertThat(persisted)
                .extracting(result -> result.chunkCount)
                .containsOnly(reloaded.totalChunks);
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
    void validate90BTimeWindowTruncatesLargeInputAndPersistsResult() {
        clearAll();
        service.setSp80090bOverride(sp80090bSuccess());
        service.setSp80090bMaxBytesForTesting(128);

        Instant end = Instant.now();
        Instant start = end.minusSeconds(300);
        seedEntropyEvents("validate-90b", start, 12);

        NIST90BResultDTO dto = service.validate90BTimeWindow(start, end);

        assertThat(dto.passed()).isTrue();
        assertThat(dto.bitsTested()).isEqualTo(128L * 8L);
        assertThat(Nist90BResult.count()).isEqualTo(1);
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
                .hasMessageContaining("No results found for assessment run");
    }

    @Test
    @TestTransaction
    void getSp80090bJobResultReturnsFirstPersistedResult() {
        clearAll();
        UUID assessmentRunId = UUID.randomUUID();
        Instant end = Instant.now();
        Instant start = end.minusSeconds(120);

        NistValidationJob job =
                persistJob(ValidationType.SP_800_90B, JobStatus.COMPLETED, start, end);
        job.assessmentRunId = assessmentRunId;
        job.persist();

        Nist90BResult result =
                new Nist90BResult("batch-1", 7.5, true, "{\"summary\":\"ok\"}", 4096L, start, end);
        result.assessmentRunId = assessmentRunId;
        result.persist();

        NIST90BResultDTO dto = service.getSp80090bJobResult(job.id);
        assertThat(dto.minEntropy()).isEqualTo(7.5);
        assertThat(dto.bitsTested()).isEqualTo(4096L);
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

    private void clearAll() {
        NistValidationJob.deleteAll();
        NistTestResult.deleteAll();
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
                            assertThat(Nist90BResult.count("assessmentRunId", job.assessmentRunId))
                                    .isGreaterThan(0);
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
