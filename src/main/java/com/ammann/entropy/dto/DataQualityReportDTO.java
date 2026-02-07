package com.ammann.entropy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Data transfer object representing a comprehensive data quality assessment
 * for a window of entropy events.
 *
 * <p>Includes metrics for sequence gap detection (packet loss), clock drift
 * between the Raspberry Pi and the server, decay rate plausibility, network
 * delay, and an overall composite quality score in the range [0.0, 1.0].
 */
@Schema(description = "Data quality assessment report for entropy event streams")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DataQualityReportDTO(
        @Schema(description = "Total number of events analyzed")
        Long totalEvents,

        @Schema(description = "List of sequence gaps represented as ranges (memory-efficient)")
        List<SequenceGapDTO> sequenceGaps,

        @Schema(description = "Number of distinct sequence gaps")
        Integer gapCount,

        @Schema(description = "Count of missing sequences (total across all gaps)")
        Long missingSequenceCount,

        @Schema(description = "Deprecated: Always empty. Use sequenceGaps for gap information.", deprecated = true)
        List<Long> missingSequences,

        @Schema(description = "Clock drift analysis between Raspi and server")
        ClockDriftInfoDTO clockDrift,

        @Schema(description = "Entropy decay rate plausibility check")
        DecayRateInfoDTO decayRate,

        @Schema(description = "Average network delay in milliseconds")
        Double averageNetworkDelayMs,

        @Schema(description = "Overall quality score (0.0 - 1.0)")
        Double qualityScore,

        @Schema(description = "Timestamp of assessment")
        Instant assessmentTimestamp
) {
}
