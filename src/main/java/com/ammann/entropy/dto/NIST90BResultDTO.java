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
                java.util.UUID assessmentRunId,
        @Schema(
                        description =
                                "When true, this is the single canonical result for the completed"
                                        + " assessment run. When false, this is a per-sample"
                                        + " row retained for forensic analysis.")
                boolean isRunSummary,
        @Schema(description = "1-based index of this sample within the window assessment")
                Integer sampleIndex,
        @Schema(description = "Total number of samples in this window assessment")
                Integer sampleCount,
        @Schema(description = "Start byte offset (inclusive) within the assembled bitstream")
                Long sampleByteOffsetStart,
        @Schema(description = "End byte offset (exclusive) within the assembled bitstream")
                Long sampleByteOffsetEnd,
        @Schema(
                        description =
                                "hwTimestampNs of the first entropy event contributing to this"
                                        + " sample (exact, not approximate)")
                Instant sampleFirstEventTimestamp,
        @Schema(
                        description =
                                "hwTimestampNs of the last entropy event contributing to this"
                                        + " sample (exact, not approximate)")
                Instant sampleLastEventTimestamp,
        @Schema(
                        description =
                                "NIST_SINGLE_SAMPLE: individual NIST-valid 90B assessment."
                                        + " PRODUCT_WINDOW_SUMMARY: product-defined conservative"
                                        + " summary across N independent NIST assessments (not"
                                        + " NIST-specified).")
                String assessmentScope,
        @Schema(
                        description =
                                "Whether the actual sample size meets the NIST SP 800-90B §3.1.2"
                                    + " minimum recommendation of 1,000,000 bytes. Null for legacy"
                                    + " or summary rows.")
                Boolean sampleSizeMeetsNistMinimum) {
    /**
     * Converts entity to DTO.
     */
    public static NIST90BResultDTO from(Nist90BResult entity) {
        return entity.toDTO();
    }
}
