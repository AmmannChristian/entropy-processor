/* (C)2026 */
package com.ammann.entropy.dto;

import com.ammann.entropy.model.Nist90BResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        description =
                "Aggregate result of NIST SP 800-90B entropy assessment. Individual estimator"
                        + " results (14 total: 10 Non-IID + 4 IID) are available via"
                        + " /90b-results/{assessmentRunId}/estimators")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NIST90BResultDTO(
        @Schema(description = "Overall min-entropy estimate (most conservative bound)")
                Double minEntropy,
        @Schema(description = "Whether the overall assessment passed") boolean passed,
        @Schema(description = "Assessment summary (JSON/text)") String assessmentDetails,
        @Schema(description = "Timestamp when assessment was executed") Instant executedAt,
        @Schema(description = "Number of bits assessed") long bitsTested,
        @Schema(description = "Time window of source data") TimeWindowDTO window,
        @Schema(
                        description =
                                "Assessment run identifier for fetching detailed estimator results"
                                        + " via /90b-results/{assessmentRunId}/estimators")
                java.util.UUID assessmentRunId) {
    /**
     * Converts entity to DTO.
     */
    public static NIST90BResultDTO from(Nist90BResult entity) {
        return entity.toDTO();
    }
}
