/* (C)2026 */
package com.ammann.entropy.dto;

import com.ammann.entropy.model.NistValidationJob;
import java.time.Instant;
import java.util.UUID;

/**
 * DTO for NIST validation job status.
 * <p>
 * Used by polling endpoints to track async job progress.
 */
public record NistValidationJobDTO(
        UUID jobId,
        String validationType,
        String status,
        Integer progressPercent,
        Integer currentChunk,
        Integer totalChunks,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        String errorMessage,
        String createdBy,
        TimeWindowDTO dataWindow) {
    /**
     * Convert entity to DTO.
     *
     * @param job Job entity
     * @return DTO representation
     */
    public static NistValidationJobDTO from(NistValidationJob job) {
        return new NistValidationJobDTO(
                job.id,
                job.validationType.name(),
                job.status.name(),
                job.progressPercent,
                job.currentChunk,
                job.totalChunks,
                job.createdAt,
                job.startedAt,
                job.completedAt,
                job.errorMessage,
                job.createdBy,
                new TimeWindowDTO(
                        job.windowStart,
                        job.windowEnd,
                        java.time.Duration.between(job.windowStart, job.windowEnd).toHours()));
    }

    /**
     * Check if job is currently active (not finished).
     *
     * @return true if job is QUEUED or RUNNING
     */
    public boolean isActive() {
        return "QUEUED".equals(status) || "RUNNING".equals(status);
    }

    /**
     * Check if job has completed successfully.
     *
     * @return true if status is COMPLETED
     */
    public boolean isCompleted() {
        return "COMPLETED".equals(status);
    }

    /**
     * Check if job has failed.
     *
     * @return true if status is FAILED
     */
    public boolean isFailed() {
        return "FAILED".equals(status);
    }
}
