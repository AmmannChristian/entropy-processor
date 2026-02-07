package com.ammann.entropy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "API error response")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponseDTO(
        @Schema(description = "Error message describing what went wrong")
        String message,

        @Schema(description = "Error code for programmatic handling")
        String errorCode,

        @Schema(description = "Timestamp when the error occurred")
        Instant timestamp
) {
    public ErrorResponseDTO(String message) {
        this(message, null, Instant.now());
    }

    public ErrorResponseDTO(String message, String errorCode) {
        this(message, errorCode, Instant.now());
    }
}