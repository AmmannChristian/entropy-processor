package com.ammann.entropy.dto;

import com.ammann.entropy.service.EntropyStatisticsService;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Comprehensive entropy statistics for a time window including Shannon, Renyi, Sample, and Approximate Entropy")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EntropyStatisticsDTO(
        @Schema(description = "Shannon entropy in bits based on classical information theory")
        Double shannonEntropy,

        @Schema(description = "Renyi entropy in bits with alpha parameter 2.0")
        Double renyiEntropy,

        @Schema(description = "Sample entropy (dimensionless measure of time-series regularity)")
        Double sampleEntropy,

        @Schema(description = "Approximate entropy (dimensionless measure of pattern complexity)")
        Double approximateEntropy,

        @Schema(description = "Number of samples used for calculation")
        Long sampleCount,

        @Schema(description = "Start of time window in ISO-8601 format")
        Instant windowStart,

        @Schema(description = "End of time window in ISO-8601 format")
        Instant windowEnd,

        @Schema(description = "Processing time in nanoseconds")
        Long processingTimeNs,

        @Schema(description = "Basic statistical measures of the interval data")
        BasicStatisticsDTO basicStats
) {
    /**
     * Converts EntropyStatisticsService result to DTO.
     *
     * @param result Service calculation result
     * @param windowStart Start of analysis window
     * @param windowEnd End of analysis window
     * @return DTO ready for JSON serialization
     */
    public static EntropyStatisticsDTO from(
            EntropyStatisticsService.EntropyAnalysisResult result,
            Instant windowStart,
            Instant windowEnd) {
        return new EntropyStatisticsDTO(
                result.shannonEntropy(),
                result.renyiEntropy(),
                result.sampleEntropy(),
                result.approximateEntropy(),
                (long) result.sampleCount(),
                windowStart,
                windowEnd,
                result.processingTimeNanos(),
                BasicStatisticsDTO.from(result.basicStats())
        );
    }
}