/* (C)2026 */
package com.ammann.entropy.startup;

import static org.assertj.core.api.Assertions.assertThat;

import com.ammann.entropy.enumeration.JobStatus;
import com.ammann.entropy.enumeration.ValidationType;
import com.ammann.entropy.model.NistValidationJob;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.Test;

@QuarkusTest
class NistJobRecoveryServiceTest {

    @Inject NistJobRecoveryService service;

    @Test
    @TestTransaction
    void onStartMarksQueuedAndRunningJobsAsFailed() {
        NistValidationJob.deleteAll();

        NistValidationJob queued = persistJob(JobStatus.QUEUED);
        NistValidationJob running = persistJob(JobStatus.RUNNING);
        NistValidationJob completed = persistJob(JobStatus.COMPLETED);

        service.onStart(null);
        NistValidationJob.getEntityManager().clear();

        NistValidationJob queuedReloaded = NistValidationJob.findById(queued.id);
        NistValidationJob runningReloaded = NistValidationJob.findById(running.id);
        NistValidationJob completedReloaded = NistValidationJob.findById(completed.id);

        assertThat(queuedReloaded.status).isEqualTo(JobStatus.FAILED);
        assertThat(queuedReloaded.errorMessage).contains("before job could start");
        assertThat(queuedReloaded.completedAt).isNotNull();

        assertThat(runningReloaded.status).isEqualTo(JobStatus.FAILED);
        assertThat(runningReloaded.errorMessage).contains("during job processing");
        assertThat(runningReloaded.completedAt).isNotNull();

        assertThat(completedReloaded.status).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    @TestTransaction
    void onStartDoesNothingWhenNoOrphanedJobsExist() {
        NistValidationJob.deleteAll();
        persistJob(JobStatus.COMPLETED);
        persistJob(JobStatus.FAILED);

        service.onStart(null);
        NistValidationJob.getEntityManager().clear();

        assertThat(NistValidationJob.count("status", JobStatus.QUEUED)).isZero();
        assertThat(NistValidationJob.count("status", JobStatus.RUNNING)).isZero();
        assertThat(NistValidationJob.count("status", JobStatus.COMPLETED)).isEqualTo(1);
        assertThat(NistValidationJob.count("status", JobStatus.FAILED)).isEqualTo(1);
    }

    private NistValidationJob persistJob(JobStatus status) {
        NistValidationJob job = new NistValidationJob();
        job.validationType = ValidationType.SP_800_22;
        job.status = status;
        job.windowStart = Instant.now().minusSeconds(3600);
        job.windowEnd = Instant.now();
        job.createdBy = "recovery-test";
        if (status == JobStatus.RUNNING) {
            job.startedAt = Instant.now().minusSeconds(60);
        }
        if (status == JobStatus.COMPLETED || status == JobStatus.FAILED) {
            job.completedAt = Instant.now().minusSeconds(10);
        }
        job.persist();
        return job;
    }
}
