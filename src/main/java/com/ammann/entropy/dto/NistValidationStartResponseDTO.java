/* (C)2026 */
package com.ammann.entropy.dto;

import java.util.UUID;

/**
 * DTO for NIST validation job creation response.
 * <p>
 * Returned immediately after starting an async validation job.
 * Contains the job ID that clients use for polling status and fetching results.
 */
public record NistValidationStartResponseDTO(UUID jobId, String status, String message) {
    /**
     * Create a success response for a queued job.
     *
     * @param jobId UUID of the created job
     * @param validationType Type of validation (SP_800_22 or SP_800_90B)
     * @return Start response DTO
     */
    public static NistValidationStartResponseDTO queued(UUID jobId, String validationType) {
        return new NistValidationStartResponseDTO(
                jobId,
                "QUEUED",
                String.format(
                        "%s validation job started. Use GET /api/v1/entropy/nist/validate/status/%s"
                                + " to check progress.",
                        validationType, jobId));
    }
}
