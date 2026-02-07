package com.ammann.entropy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "Recent entropy events response")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecentEventsResponseDTO(
        @Schema(description = "List of recent events")
        List<EventSummaryDTO> events,

        @Schema(description = "Number of events returned")
        Integer count,

        @Schema(description = "Timestamp of oldest event in response")
        Instant oldestEvent,

        @Schema(description = "Timestamp of newest event in response")
        Instant newestEvent
) {
    @Schema(description = "Summary of a single entropy event")
    public record EventSummaryDTO(
            @Schema(description = "Database ID of the event")
            Long id,

            @Schema(description = "Hardware timestamp in nanoseconds")
            Long hwTimestampNs,

            @Schema(description = "Sequence number for packet loss detection")
            Long sequenceNumber,

            @Schema(description = "Server reception timestamp")
            Instant serverReceived,

            @Schema(description = "Network delay in milliseconds")
            Long networkDelayMs,

            @Schema(description = "Data quality score from 0.0 to 1.0")
            Double qualityScore,

            @Schema(description = "Interval to previous event in nanoseconds")
            Long intervalToPreviousNs
    ) {}
}