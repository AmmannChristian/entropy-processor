/* (C)2026 */
package com.ammann.entropy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Recent entropy events response")
@JsonInclude(JsonInclude.Include.NON_NULL)
/**
 * Response envelope for recent entropy events in a requested time scope.
 *
 * @param events ordered event summaries included in the response
 * @param count number of returned events
 * @param oldestEvent timestamp of the oldest included event
 * @param newestEvent timestamp of the newest included event
 */
public record RecentEventsResponseDTO(
        @Schema(description = "List of recent events") List<EventSummaryDTO> events,
        @Schema(description = "Number of events returned") Integer count,
        @Schema(description = "Timestamp of oldest event in response") Instant oldestEvent,
        @Schema(description = "Timestamp of newest event in response") Instant newestEvent) {
    /**
     * Reduced event projection for list responses.
     *
     * @param id database identifier
     * @param hwTimestampNs hardware timestamp in nanoseconds
     * @param sequenceNumber acquisition sequence number
     * @param serverReceived backend reception timestamp
     * @param networkDelayMs measured network delay in milliseconds
     * @param qualityScore normalized quality score in the range [0.0, 1.0]
     * @param intervalToPreviousNs interval to the previous event in nanoseconds
     */
    @Schema(description = "Summary of a single entropy event")
    public record EventSummaryDTO(
            @Schema(description = "Database ID of the event") Long id,
            @Schema(description = "Hardware timestamp in nanoseconds") Long hwTimestampNs,
            @Schema(description = "Sequence number for packet loss detection") Long sequenceNumber,
            @Schema(description = "Server reception timestamp") Instant serverReceived,
            @Schema(description = "Network delay in milliseconds") Long networkDelayMs,
            @Schema(description = "Data quality score from 0.0 to 1.0") Double qualityScore,
            @Schema(description = "Interval to previous event in nanoseconds")
                    Long intervalToPreviousNs) {}
}
