/* (C)2026 */
package com.ammann.entropy.startup;

import com.ammann.entropy.model.NistValidationJob;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * Recovers orphaned NIST validation jobs on application startup.
 * <p>
 * When the entropy-processor service restarts (due to crash, deployment, or container restart),
 * any jobs that were QUEUED or RUNNING at the time of shutdown become orphaned. This service
 * marks them as FAILED to prevent them from remaining stuck indefinitely.
 * <p>
 * This is necessary because async jobs run in background threads that don't survive
 * application restarts.
 */
@ApplicationScoped
public class NistJobRecoveryService {

    private static final Logger LOG = Logger.getLogger(NistJobRecoveryService.class);

    /**
     * Executed on application startup.
     * <p>
     * Marks all QUEUED and RUNNING jobs as FAILED with an explanatory error message.
     * This ensures users understand why their jobs didn't complete and can re-trigger if needed.
     *
     * @param event Quarkus startup event
     */
    @Transactional
    void onStart(@Observes StartupEvent event) {
        LOG.info("NIST job recovery: checking for orphaned jobs...");

        long updatedQueued =
                NistValidationJob.update(
                        "status = 'FAILED', "
                                + "errorMessage = 'Server restarted before job could start', "
                                + "completedAt = NOW() "
                                + "WHERE status = 'QUEUED'");

        long updatedRunning =
                NistValidationJob.update(
                        "status = 'FAILED', "
                                + "errorMessage = 'Server restarted during job processing', "
                                + "completedAt = NOW() "
                                + "WHERE status = 'RUNNING'");

        long totalRecovered = updatedQueued + updatedRunning;

        if (totalRecovered > 0) {
            LOG.warnf(
                    "NIST job recovery: marked %d orphaned jobs as FAILED (queued=%d, running=%d)",
                    totalRecovered, updatedQueued, updatedRunning);
        } else {
            LOG.info("NIST job recovery: no orphaned jobs found");
        }
    }
}
