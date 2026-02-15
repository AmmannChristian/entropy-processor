/* (C)2026 */
package com.ammann.entropy.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.ammann.entropy.enumeration.JobStatus;
import com.ammann.entropy.enumeration.ValidationType;
import com.ammann.entropy.model.NistValidationJob;
import com.ammann.entropy.support.TestDataFactory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NistValidationJobDTOTest {

    @Test
    void fromConvertsAllFieldsCorrectly() {
        Instant now = Instant.now();
        Instant start = now.minus(2, ChronoUnit.HOURS);
        Instant end = now.minus(1, ChronoUnit.HOURS);

        NistValidationJob job =
                TestDataFactory.createNistValidationJob(
                        ValidationType.SP_800_22, JobStatus.RUNNING, start, end, "alice");
        job.id = UUID.randomUUID();
        job.progressPercent = 45;
        job.currentChunk = 2;
        job.totalChunks = 5;
        job.startedAt = now.minus(10, ChronoUnit.MINUTES);

        NistValidationJobDTO dto = NistValidationJobDTO.from(job);

        assertThat(dto.jobId()).isEqualTo(job.id);
        assertThat(dto.validationType()).isEqualTo("SP_800_22");
        assertThat(dto.status()).isEqualTo("RUNNING");
        assertThat(dto.progressPercent()).isEqualTo(45);
        assertThat(dto.currentChunk()).isEqualTo(2);
        assertThat(dto.totalChunks()).isEqualTo(5);
        assertThat(dto.createdAt()).isEqualTo(job.createdAt);
        assertThat(dto.startedAt()).isEqualTo(job.startedAt);
        assertThat(dto.completedAt()).isNull();
        assertThat(dto.errorMessage()).isNull();
        assertThat(dto.createdBy()).isEqualTo("alice");
    }

    @Test
    void fromCalculatesWindowDurationInHours() {
        Instant start = Instant.parse("2024-01-01T10:00:00Z");
        Instant end = Instant.parse("2024-01-01T13:30:00Z");

        NistValidationJob job =
                TestDataFactory.createNistValidationJob(
                        ValidationType.SP_800_22, JobStatus.COMPLETED, start, end, "test-user");

        NistValidationJobDTO dto = NistValidationJobDTO.from(job);

        assertThat(dto.dataWindow().start()).isEqualTo(start);
        assertThat(dto.dataWindow().end()).isEqualTo(end);
        assertThat(dto.dataWindow().durationHours()).isEqualTo(3);
    }

    @Test
    void fromHandlesCompletedJob() {
        Instant now = Instant.now();
        UUID runId = UUID.randomUUID();

        NistValidationJob job =
                TestDataFactory.createCompletedNistJob(
                        ValidationType.SP_800_90B,
                        now.minus(2, ChronoUnit.HOURS),
                        now.minus(1, ChronoUnit.HOURS),
                        runId);
        job.id = UUID.randomUUID();

        NistValidationJobDTO dto = NistValidationJobDTO.from(job);

        assertThat(dto.status()).isEqualTo("COMPLETED");
        assertThat(dto.progressPercent()).isEqualTo(100);
        assertThat(dto.completedAt()).isNotNull();
    }

    @Test
    void fromHandlesFailedJobWithErrorMessage() {
        Instant now = Instant.now();

        NistValidationJob job =
                TestDataFactory.createFailedNistJob(
                        ValidationType.SP_800_22,
                        now.minus(2, ChronoUnit.HOURS),
                        now.minus(1, ChronoUnit.HOURS),
                        "gRPC connection failed");
        job.id = UUID.randomUUID();

        NistValidationJobDTO dto = NistValidationJobDTO.from(job);

        assertThat(dto.status()).isEqualTo("FAILED");
        assertThat(dto.errorMessage()).isEqualTo("gRPC connection failed");
        assertThat(dto.completedAt()).isNotNull();
    }

    @Test
    void isActiveReturnsTrueForQueuedAndRunning() {
        Instant now = Instant.now();

        NistValidationJob queuedJob =
                TestDataFactory.createNistValidationJob(
                        ValidationType.SP_800_22,
                        JobStatus.QUEUED,
                        now,
                        now.plus(1, ChronoUnit.HOURS),
                        "test-user");
        NistValidationJob runningJob =
                TestDataFactory.createNistValidationJob(
                        ValidationType.SP_800_22,
                        JobStatus.RUNNING,
                        now,
                        now.plus(1, ChronoUnit.HOURS),
                        "test-user");

        assertThat(NistValidationJobDTO.from(queuedJob).isActive()).isTrue();
        assertThat(NistValidationJobDTO.from(runningJob).isActive()).isTrue();
    }

    @Test
    void isActiveReturnsFalseForCompletedAndFailed() {
        Instant now = Instant.now();

        NistValidationJob completedJob =
                TestDataFactory.createCompletedNistJob(
                        ValidationType.SP_800_22,
                        now.minus(2, ChronoUnit.HOURS),
                        now.minus(1, ChronoUnit.HOURS),
                        UUID.randomUUID());
        NistValidationJob failedJob =
                TestDataFactory.createFailedNistJob(
                        ValidationType.SP_800_22,
                        now.minus(2, ChronoUnit.HOURS),
                        now.minus(1, ChronoUnit.HOURS),
                        "Error");

        assertThat(NistValidationJobDTO.from(completedJob).isActive()).isFalse();
        assertThat(NistValidationJobDTO.from(failedJob).isActive()).isFalse();
    }

    @Test
    void isCompletedReturnsTrueOnlyForCompleted() {
        Instant now = Instant.now();

        NistValidationJob completedJob =
                TestDataFactory.createCompletedNistJob(
                        ValidationType.SP_800_22,
                        now.minus(2, ChronoUnit.HOURS),
                        now.minus(1, ChronoUnit.HOURS),
                        UUID.randomUUID());
        NistValidationJob queuedJob =
                TestDataFactory.createNistValidationJob(
                        ValidationType.SP_800_22,
                        JobStatus.QUEUED,
                        now,
                        now.plus(1, ChronoUnit.HOURS),
                        "test-user");

        assertThat(NistValidationJobDTO.from(completedJob).isCompleted()).isTrue();
        assertThat(NistValidationJobDTO.from(queuedJob).isCompleted()).isFalse();
    }

    @Test
    void isFailedReturnsTrueOnlyForFailed() {
        Instant now = Instant.now();

        NistValidationJob failedJob =
                TestDataFactory.createFailedNistJob(
                        ValidationType.SP_800_22,
                        now.minus(2, ChronoUnit.HOURS),
                        now.minus(1, ChronoUnit.HOURS),
                        "Error");
        NistValidationJob completedJob =
                TestDataFactory.createCompletedNistJob(
                        ValidationType.SP_800_22,
                        now.minus(2, ChronoUnit.HOURS),
                        now.minus(1, ChronoUnit.HOURS),
                        UUID.randomUUID());

        assertThat(NistValidationJobDTO.from(failedJob).isFailed()).isTrue();
        assertThat(NistValidationJobDTO.from(completedJob).isFailed()).isFalse();
    }
}
