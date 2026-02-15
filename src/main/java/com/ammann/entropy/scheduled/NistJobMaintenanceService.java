/* (C)2026 */
package com.ammann.entropy.scheduled;

import com.ammann.entropy.model.NistValidationJob;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import org.jboss.logging.Logger;

/**
 * Scheduled maintenance service for NIST validation jobs.
 * <p>
 * Provides two critical functions:
 * <ol>
 *   <li><b>Watchdog:</b> Detects and fails stuck RUNNING jobs that exceed maximum runtime (30 minutes)</li>
 *   <li><b>Cleanup:</b> Deletes old COMPLETED/FAILED jobs to prevent table bloat (7-day retention)</li>
 * </ol>
 * <p>
 * The watchdog prevents resource leaks from jobs that never complete due to unexpected errors,
 * while cleanup maintains database performance by removing obsolete job records.
 */
@ApplicationScoped
public class NistJobMaintenanceService {

    private static final Logger LOG = Logger.getLogger(NistJobMaintenanceService.class);

    /** Maximum allowed runtime for a NIST validation job before it's considered stuck */
    private static final Duration MAX_JOB_RUNTIME = Duration.ofMinutes(30);

    /** Retention period for completed/failed jobs before automatic deletion */
    private static final Duration JOB_RETENTION_PERIOD = Duration.ofDays(7);

    /**
     * Watchdog: detects and fails stuck jobs.
     * <p>
     * Runs every 10 minutes to check for RUNNING jobs that have been executing for more than
     * 30 minutes. Such jobs are marked as FAILED because normal NIST validations complete
     * within a few minutes (typical: 2-5 minutes for moderate-sized bitstreams).
     * <p>
     * Stuck jobs can occur from:
     * <ul>
     *   <li>gRPC service hangs</li>
     *   <li>Database connection timeouts</li>
     *   <li>Uncaught exceptions in async worker threads</li>
     * </ul>
     */
    @Scheduled(every = "10m", identity = "nist-job-watchdog")
    @Transactional
    public void detectStuckJobs() {
        Instant threshold = Instant.now().minus(MAX_JOB_RUNTIME);

        long marked =
                NistValidationJob.update(
                        "status = 'FAILED', "
                                + "errorMessage = 'Job exceeded maximum runtime (30 minutes)', "
                                + "completedAt = NOW() "
                                + "WHERE status = 'RUNNING' AND startedAt < ?1",
                        threshold);

        if (marked > 0) {
            LOG.warnf(
                    "Watchdog: marked %d stuck NIST validation jobs as FAILED (runtime > 30"
                            + " minutes)",
                    marked);
        }
    }

    /**
     * Cleanup: deletes old job records.
     * <p>
     * Runs weekly (Sunday at 2 AM) to delete COMPLETED and FAILED jobs older than 7 days.
     * This prevents the nist_validation_jobs table from growing indefinitely while retaining
     * recent job history for debugging and user reference.
     * <p>
     * Retention period matches the NIST test results retention (7 days via TimescaleDB
     * retention policy), ensuring consistency across related tables.
     * <p>
     * Jobs are hard-deleted (not archived) because:
     * <ul>
     *   <li>Test results remain available via test_suite_run_id / assessment_run_id</li>
     *   <li>Job records are primarily for progress tracking, not long-term audit</li>
     *   <li>Users can re-run validations if historical data is needed</li>
     * </ul>
     */
    @Scheduled(cron = "0 0 2 * * SUN", identity = "nist-job-cleanup")
    @Transactional
    public void cleanupOldJobs() {
        Instant cutoff = Instant.now().minus(JOB_RETENTION_PERIOD);

        long deleted =
                NistValidationJob.delete(
                        "createdAt < ?1 AND (status = 'COMPLETED' OR status = 'FAILED')", cutoff);

        if (deleted > 0) {
            LOG.infof(
                    "Cleanup: deleted %d old NIST validation jobs (created before %s)",
                    deleted, cutoff);
        } else {
            LOG.debug("Cleanup: no old NIST validation jobs to delete");
        }
    }
}
