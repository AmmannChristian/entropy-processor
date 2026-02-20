/* (C)2026 */
package com.ammann.entropy.scheduled;

import static org.assertj.core.api.Assertions.assertThat;

import com.ammann.entropy.enumeration.JobStatus;
import com.ammann.entropy.enumeration.ValidationType;
import com.ammann.entropy.model.NistValidationJob;
import com.ammann.entropy.service.NistJobMaintenanceService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.Test;

@QuarkusTest
class NistJobMaintenanceServiceTest {

    @Inject
    NistJobMaintenanceService service;

    @Test
    @TestTransaction
    void detectStuckJobsMarksOnlyOldRunningJobsAsFailed() {
        NistValidationJob.deleteAll();

        NistValidationJob stuck = persistJob(JobStatus.RUNNING, Instant.now().minusSeconds(3600));
        NistValidationJob fresh = persistJob(JobStatus.RUNNING, Instant.now().minusSeconds(300));
        NistValidationJob queued = persistJob(JobStatus.QUEUED, null);

        service.detectStuckJobs();
        NistValidationJob.getEntityManager().clear();

        NistValidationJob stuckReloaded = NistValidationJob.findById(stuck.id);
        NistValidationJob freshReloaded = NistValidationJob.findById(fresh.id);
        NistValidationJob queuedReloaded = NistValidationJob.findById(queued.id);

        assertThat(stuckReloaded.status).isEqualTo(JobStatus.FAILED);
        assertThat(stuckReloaded.errorMessage).contains("maximum runtime");
        assertThat(stuckReloaded.completedAt).isNotNull();

        assertThat(freshReloaded.status).isEqualTo(JobStatus.RUNNING);
        assertThat(freshReloaded.errorMessage).isNull();
        assertThat(queuedReloaded.status).isEqualTo(JobStatus.QUEUED);
    }

    @Test
    @TestTransaction
    void detectStuckJobsMarksOldQueuedJobsAsFailed() {
        NistValidationJob.deleteAll();

        NistValidationJob oldQueued =
                persistJob(JobStatus.QUEUED, null, Instant.now().minusSeconds(3600));
        NistValidationJob freshQueued = persistJob(JobStatus.QUEUED, null);

        service.detectStuckJobs();
        NistValidationJob.getEntityManager().clear();

        NistValidationJob oldQueuedReloaded = NistValidationJob.findById(oldQueued.id);
        NistValidationJob freshQueuedReloaded = NistValidationJob.findById(freshQueued.id);

        assertThat(oldQueuedReloaded.status).isEqualTo(JobStatus.FAILED);
        assertThat(oldQueuedReloaded.errorMessage).contains("queued");
        assertThat(oldQueuedReloaded.completedAt).isNotNull();

        assertThat(freshQueuedReloaded.status).isEqualTo(JobStatus.QUEUED);
        assertThat(freshQueuedReloaded.errorMessage).isNull();
    }

    @Test
    @TestTransaction
    void detectStuckJobsKeepsStateWhenNoStuckJobsExist() {
        NistValidationJob.deleteAll();

        persistJob(JobStatus.RUNNING, Instant.now().minusSeconds(60));
        persistJob(JobStatus.QUEUED, null);

        service.detectStuckJobs();
        NistValidationJob.getEntityManager().clear();

        assertThat(NistValidationJob.count("status", JobStatus.FAILED)).isZero();
        assertThat(NistValidationJob.count("status", JobStatus.RUNNING)).isEqualTo(1);
        assertThat(NistValidationJob.count("status", JobStatus.QUEUED)).isEqualTo(1);
    }

    @Test
    @TestTransaction
    void cleanupOldJobsDeletesOnlyOldCompletedAndFailedJobs() {
        NistValidationJob.deleteAll();
        Instant now = Instant.now();

        persistJob(JobStatus.COMPLETED, now.minusSeconds(120), now.minusSeconds(8 * 24 * 3600));
        persistJob(JobStatus.FAILED, now.minusSeconds(120), now.minusSeconds(10 * 24 * 3600));
        persistJob(JobStatus.RUNNING, now.minusSeconds(120), now.minusSeconds(10 * 24 * 3600));
        persistJob(JobStatus.QUEUED, null, now.minusSeconds(10 * 24 * 3600));
        persistJob(JobStatus.COMPLETED, now.minusSeconds(120), now.minusSeconds(2 * 24 * 3600));

        service.cleanupOldJobs();
        NistValidationJob.getEntityManager().clear();

        assertThat(NistValidationJob.count()).isEqualTo(3);
        assertThat(NistValidationJob.count("status", JobStatus.RUNNING)).isEqualTo(1);
        assertThat(NistValidationJob.count("status", JobStatus.QUEUED)).isEqualTo(1);
        assertThat(NistValidationJob.count("status", JobStatus.COMPLETED)).isEqualTo(1);
        assertThat(NistValidationJob.count("status", JobStatus.FAILED)).isZero();
    }

    private NistValidationJob persistJob(JobStatus status, Instant startedAt) {
        return persistJob(status, startedAt, Instant.now());
    }

    private NistValidationJob persistJob(JobStatus status, Instant startedAt, Instant createdAt) {
        NistValidationJob job = new NistValidationJob();
        job.validationType = ValidationType.SP_800_22;
        job.status = status;
        job.progressPercent = status == JobStatus.COMPLETED ? 100 : 0;
        job.windowStart = Instant.now().minusSeconds(3600);
        job.windowEnd = Instant.now();
        job.createdBy = "maintenance-test";
        job.startedAt = startedAt;
        job.createdAt = createdAt;
        if (status == JobStatus.COMPLETED || status == JobStatus.FAILED) {
            job.completedAt = Instant.now().minusSeconds(30);
        }
        job.persist();
        return job;
    }
}
