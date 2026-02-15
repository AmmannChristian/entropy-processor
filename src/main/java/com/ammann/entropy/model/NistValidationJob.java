/* (C)2026 */
package com.ammann.entropy.model;

import com.ammann.entropy.enumeration.JobStatus;
import com.ammann.entropy.enumeration.ValidationType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Entity representing an async NIST validation job.
 * <p>
 * Supports both NIST SP 800-22 (randomness tests) and NIST SP 800-90B (entropy assessment)
 * validations through the validation_type field. Jobs track progress through chunk processing
 * and enable async/polling pattern for long-running operations.
 * <p>
 * Job lifecycle: QUEUED -> RUNNING -> (COMPLETED | FAILED)
 */
@Entity
@Table(name = "nist_validation_jobs")
public class NistValidationJob extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(name = "validation_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    public ValidationType validationType;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    public JobStatus status;

    @Column(name = "progress_percent")
    public Integer progressPercent = 0;

    @Column(name = "current_chunk")
    public Integer currentChunk = 0;

    @Column(name = "total_chunks")
    public Integer totalChunks;

    @Column(name = "window_start", nullable = false)
    public Instant windowStart;

    @Column(name = "window_end", nullable = false)
    public Instant windowEnd;

    @Column(name = "test_suite_run_id")
    public UUID testSuiteRunId; // For SP_800_22 jobs

    @Column(name = "assessment_run_id")
    public UUID assessmentRunId; // For SP_800_90B jobs

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "started_at")
    public Instant startedAt;

    @Column(name = "completed_at")
    public Instant completedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    public String errorMessage;

    @Column(name = "created_by")
    public String createdBy;

    // Finder methods

    /**
     * Find job by ID.
     *
     * @param id Job UUID
     * @return Job entity or null if not found
     */
    public static NistValidationJob findByIdOptional(UUID id) {
        return find("id", id).firstResult();
    }

    /**
     * Find recent jobs, optionally filtered by user.
     *
     * @param limit Maximum number of jobs to return
     * @param username Optional username filter (null for all users)
     * @return List of recent jobs, most recent first
     */
    public static List<NistValidationJob> findRecent(int limit, String username) {
        if (username != null && !username.isBlank()) {
            return find("createdBy = ?1 ORDER BY createdAt DESC", username)
                    .page(0, limit)
                    .list();
        }
        return find("ORDER BY createdAt DESC")
                .page(0, limit)
                .list();
    }

    /**
     * Count jobs by status.
     *
     * @param status Job status to count
     * @return Number of jobs with the given status
     */
    public static long countByStatus(JobStatus status) {
        return count("status", status);
    }

    /**
     * Count active jobs (QUEUED or RUNNING) for a specific user.
     *
     * @param username Username to check
     * @return Number of active jobs for the user
     */
    public static long countActiveByUser(String username) {
        return count("createdBy = ?1 AND (status = 'QUEUED' OR status = 'RUNNING')", username);
    }

    /**
     * Get the run ID (test_suite_run_id or assessment_run_id) based on validation type.
     *
     * @return Run UUID or null if not set
     */
    public UUID getRunId() {
        return validationType == ValidationType.SP_800_22 ? testSuiteRunId : assessmentRunId;
    }

    /**
     * Set the run ID based on validation type.
     *
     * @param runId Run UUID to set
     */
    public void setRunId(UUID runId) {
        if (validationType == ValidationType.SP_800_22) {
            this.testSuiteRunId = runId;
        } else {
            this.assessmentRunId = runId;
        }
    }
}
