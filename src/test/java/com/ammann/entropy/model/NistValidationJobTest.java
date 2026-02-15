/* (C)2026 */
package com.ammann.entropy.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.ammann.entropy.enumeration.JobStatus;
import com.ammann.entropy.enumeration.ValidationType;
import com.ammann.entropy.support.TestDataFactory;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class NistValidationJobTest {

    @BeforeEach
    @TestTransaction
    void cleanup() {
        NistValidationJob.deleteAll();
    }

    @Test
    @TestTransaction
    void findByIdOptionalReturnsJobWhenExists() {
        Instant now = Instant.now();
        NistValidationJob job =
                TestDataFactory.createNistValidationJob(
                        ValidationType.SP_800_22,
                        JobStatus.QUEUED,
                        now,
                        now.plus(1, ChronoUnit.HOURS),
                        "test-user");
        job.persist();

        NistValidationJob found = NistValidationJob.findByIdOptional(job.id);

        assertThat(found).isNotNull();
        assertThat(found.id).isEqualTo(job.id);
        assertThat(found.validationType).isEqualTo(ValidationType.SP_800_22);
        assertThat(found.status).isEqualTo(JobStatus.QUEUED);
    }

    @Test
    @TestTransaction
    void findByIdOptionalReturnsNullWhenNotExists() {
        UUID nonExistentId = UUID.randomUUID();

        NistValidationJob found = NistValidationJob.findByIdOptional(nonExistentId);

        assertThat(found).isNull();
    }

    @Test
    @TestTransaction
    void findRecentReturnsJobsSortedByCreatedAtDesc() {
        Instant now = Instant.now();

        NistValidationJob job1 =
                TestDataFactory.createNistValidationJob(
                        ValidationType.SP_800_22,
                        JobStatus.COMPLETED,
                        now,
                        now.plus(1, ChronoUnit.HOURS),
                        "user1");
        job1.createdAt = now.minusSeconds(30);
        job1.persist();

        NistValidationJob job2 =
                TestDataFactory.createNistValidationJob(
                        ValidationType.SP_800_90B,
                        JobStatus.RUNNING,
                        now,
                        now.plus(1, ChronoUnit.HOURS),
                        "user2");
        job2.createdAt = now.minusSeconds(10);
        job2.persist();

        NistValidationJob job3 =
                TestDataFactory.createNistValidationJob(
                        ValidationType.SP_800_22,
                        JobStatus.QUEUED,
                        now,
                        now.plus(1, ChronoUnit.HOURS),
                        "user1");
        job3.createdAt = now;
        job3.persist();

        List<NistValidationJob> recent = NistValidationJob.findRecent(10, null);

        assertThat(recent).hasSize(3);
        assertThat(recent.get(0).id).isEqualTo(job3.id); // Most recent first
        assertThat(recent.get(1).id).isEqualTo(job2.id);
        assertThat(recent.get(2).id).isEqualTo(job1.id);
    }

    @Test
    @TestTransaction
    void findRecentRespectsLimit() {
        List<NistValidationJob> jobs =
                TestDataFactory.buildSequentialJobs(
                        5, ValidationType.SP_800_22, JobStatus.COMPLETED, "test-user");
        jobs.forEach(job -> job.persist());

        List<NistValidationJob> recent = NistValidationJob.findRecent(3, null);

        assertThat(recent).hasSize(3);
    }

    @Test
    @TestTransaction
    void findRecentFiltersbyUsername() {
        Instant now = Instant.now();

        NistValidationJob job1 =
                TestDataFactory.createNistValidationJob(
                        ValidationType.SP_800_22,
                        JobStatus.COMPLETED,
                        now,
                        now.plus(1, ChronoUnit.HOURS),
                        "alice");
        job1.persist();

        NistValidationJob job2 =
                TestDataFactory.createNistValidationJob(
                        ValidationType.SP_800_90B,
                        JobStatus.RUNNING,
                        now,
                        now.plus(1, ChronoUnit.HOURS),
                        "bob");
        job2.persist();

        NistValidationJob job3 =
                TestDataFactory.createNistValidationJob(
                        ValidationType.SP_800_22,
                        JobStatus.QUEUED,
                        now,
                        now.plus(1, ChronoUnit.HOURS),
                        "alice");
        job3.persist();

        List<NistValidationJob> aliceJobs = NistValidationJob.findRecent(10, "alice");

        assertThat(aliceJobs).hasSize(2);
        assertThat(aliceJobs).extracting("createdBy").containsOnly("alice");
    }

    @Test
    @TestTransaction
    void findRecentReturnsEmptyListWhenNoJobsForUser() {
        Instant now = Instant.now();
        NistValidationJob job =
                TestDataFactory.createNistValidationJob(
                        ValidationType.SP_800_22,
                        JobStatus.COMPLETED,
                        now,
                        now.plus(1, ChronoUnit.HOURS),
                        "alice");
        job.persist();

        List<NistValidationJob> bobJobs = NistValidationJob.findRecent(10, "bob");

        assertThat(bobJobs).isEmpty();
    }

    @Test
    @TestTransaction
    void findRecentHandlesBlankUsername() {
        Instant now = Instant.now();
        NistValidationJob job1 =
                TestDataFactory.createNistValidationJob(
                        ValidationType.SP_800_22,
                        JobStatus.COMPLETED,
                        now,
                        now.plus(1, ChronoUnit.HOURS),
                        "alice");
        job1.persist();

        NistValidationJob job2 =
                TestDataFactory.createNistValidationJob(
                        ValidationType.SP_800_90B,
                        JobStatus.RUNNING,
                        now,
                        now.plus(1, ChronoUnit.HOURS),
                        "bob");
        job2.persist();

        List<NistValidationJob> allJobs = NistValidationJob.findRecent(10, "");

        assertThat(allJobs).hasSize(2);
    }

    @Test
    @TestTransaction
    void countByStatusReturnsCorrectCount() {
        Instant now = Instant.now();

        NistValidationJob.persist(
                TestDataFactory.createNistValidationJob(
                        ValidationType.SP_800_22,
                        JobStatus.QUEUED,
                        now,
                        now.plus(1, ChronoUnit.HOURS),
                        "user1"),
                TestDataFactory.createNistValidationJob(
                        ValidationType.SP_800_22,
                        JobStatus.QUEUED,
                        now,
                        now.plus(1, ChronoUnit.HOURS),
                        "user2"),
                TestDataFactory.createNistValidationJob(
                        ValidationType.SP_800_22,
                        JobStatus.RUNNING,
                        now,
                        now.plus(1, ChronoUnit.HOURS),
                        "user1"));

        assertThat(NistValidationJob.countByStatus(JobStatus.QUEUED)).isEqualTo(2);
        assertThat(NistValidationJob.countByStatus(JobStatus.RUNNING)).isEqualTo(1);
        assertThat(NistValidationJob.countByStatus(JobStatus.COMPLETED)).isZero();
    }

    @Test
    @TestTransaction
    void countByStatusReturnsZeroWhenNoJobs() {
        assertThat(NistValidationJob.countByStatus(JobStatus.QUEUED)).isZero();
    }

    @Test
    @TestTransaction
    void countActiveByUserReturnsQueuedAndRunningJobs() {
        Instant now = Instant.now();

        NistValidationJob.persist(
                TestDataFactory.createNistValidationJob(
                        ValidationType.SP_800_22,
                        JobStatus.QUEUED,
                        now,
                        now.plus(1, ChronoUnit.HOURS),
                        "alice"),
                TestDataFactory.createNistValidationJob(
                        ValidationType.SP_800_22,
                        JobStatus.RUNNING,
                        now,
                        now.plus(1, ChronoUnit.HOURS),
                        "alice"),
                TestDataFactory.createNistValidationJob(
                        ValidationType.SP_800_22,
                        JobStatus.COMPLETED,
                        now,
                        now.plus(1, ChronoUnit.HOURS),
                        "alice"),
                TestDataFactory.createNistValidationJob(
                        ValidationType.SP_800_22,
                        JobStatus.FAILED,
                        now,
                        now.plus(1, ChronoUnit.HOURS),
                        "alice"),
                TestDataFactory.createNistValidationJob(
                        ValidationType.SP_800_22,
                        JobStatus.QUEUED,
                        now,
                        now.plus(1, ChronoUnit.HOURS),
                        "bob"));

        assertThat(NistValidationJob.countActiveByUser("alice")).isEqualTo(2);
        assertThat(NistValidationJob.countActiveByUser("bob")).isEqualTo(1);
    }

    @Test
    @TestTransaction
    void countActiveByUserReturnsZeroWhenNoActiveJobs() {
        Instant now = Instant.now();
        NistValidationJob job =
                TestDataFactory.createNistValidationJob(
                        ValidationType.SP_800_22,
                        JobStatus.COMPLETED,
                        now,
                        now.plus(1, ChronoUnit.HOURS),
                        "alice");
        job.persist();

        assertThat(NistValidationJob.countActiveByUser("alice")).isZero();
    }

    @Test
    @TestTransaction
    void getRunIdReturnsTestSuiteRunIdForSp80022() {
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();

        NistValidationJob job =
                TestDataFactory.createCompletedNistJob(
                        ValidationType.SP_800_22, now, now.plus(1, ChronoUnit.HOURS), runId);
        job.persist();

        assertThat(job.getRunId()).isEqualTo(runId);
        assertThat(job.testSuiteRunId).isEqualTo(runId);
        assertThat(job.assessmentRunId).isNull();
    }

    @Test
    @TestTransaction
    void getRunIdReturnsAssessmentRunIdForSp80090b() {
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();

        NistValidationJob job =
                TestDataFactory.createCompletedNistJob(
                        ValidationType.SP_800_90B, now, now.plus(1, ChronoUnit.HOURS), runId);
        job.persist();

        assertThat(job.getRunId()).isEqualTo(runId);
        assertThat(job.assessmentRunId).isEqualTo(runId);
        assertThat(job.testSuiteRunId).isNull();
    }

    @Test
    @TestTransaction
    void setRunIdSetsTestSuiteRunIdForSp80022() {
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();

        NistValidationJob job =
                TestDataFactory.createNistValidationJob(
                        ValidationType.SP_800_22,
                        JobStatus.RUNNING,
                        now,
                        now.plus(1, ChronoUnit.HOURS),
                        "test-user");
        job.setRunId(runId);
        job.persist();

        NistValidationJob found = NistValidationJob.findByIdOptional(job.id);
        assertThat(found.testSuiteRunId).isEqualTo(runId);
        assertThat(found.assessmentRunId).isNull();
    }

    @Test
    @TestTransaction
    void setRunIdSetsAssessmentRunIdForSp80090b() {
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();

        NistValidationJob job =
                TestDataFactory.createNistValidationJob(
                        ValidationType.SP_800_90B,
                        JobStatus.RUNNING,
                        now,
                        now.plus(1, ChronoUnit.HOURS),
                        "test-user");
        job.setRunId(runId);
        job.persist();

        NistValidationJob found = NistValidationJob.findByIdOptional(job.id);
        assertThat(found.assessmentRunId).isEqualTo(runId);
        assertThat(found.testSuiteRunId).isNull();
    }
}
