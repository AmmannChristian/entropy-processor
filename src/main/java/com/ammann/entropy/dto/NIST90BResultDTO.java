package com.ammann.entropy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Result of NIST SP 800-90B entropy assessment")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NIST90BResultDTO(
        @Schema(description = "Estimated min-entropy")
        double minEntropy,

        @Schema(description = "Estimated Shannon entropy")
        double shannonEntropy,

        @Schema(description = "Collision entropy estimate")
        double collisionEntropy,

        @Schema(description = "Markov entropy estimate")
        double markovEntropy,

        @Schema(description = "Compression-based entropy estimate")
        double compressionEntropy,

        @Schema(description = "Whether assessment passed")
        boolean passed,

        @Schema(description = "Detailed assessment data (JSON/text)")
        String assessmentDetails,

        @Schema(description = "Timestamp when assessment was executed")
        Instant executedAt,

        @Schema(description = "Number of bits assessed")
        long bitsTested,

        @Schema(description = "Time window of source data")
        TimeWindowDTO window
) {
}
