/* (C)2026 */
package com.ammann.entropy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "API error response")
@JsonInclude(JsonInclude.Include.NON_NULL)
/**
 * Standardized error payload for REST responses.
 *
 * @param message human-readable error message
 * @param errorCode optional machine-readable error code
 * @param timestamp server-side error timestamp
 */
public record ErrorResponseDTO(
        @Schema(description = "Error message describing what went wrong") String message,
        @Schema(description = "Error code for programmatic handling") String errorCode,
        @Schema(description = "Timestamp when the error occurred") Instant timestamp) {
    /**
     * Creates an error payload with message only and the current timestamp.
     *
     * @param message human-readable error message
     */
    public ErrorResponseDTO(String message) {
        this(message, null, Instant.now());
    }

    /**
     * Creates an error payload with explicit code and the current timestamp.
     *
     * @param message human-readable error message
     * @param errorCode machine-readable error code
     */
    public ErrorResponseDTO(String message, String errorCode) {
        this(message, errorCode, Instant.now());
    }
}
