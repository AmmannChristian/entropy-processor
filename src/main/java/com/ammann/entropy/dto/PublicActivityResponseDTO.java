/* (C)2026 */
package com.ammann.entropy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Public recent activity summary")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicActivityResponseDTO(
        @Schema(description = "List of recent events") List<PublicEventSummaryDTO> events,
        @Schema(description = "Number of events returned") Integer count,
        @Schema(description = "Server timestamp of newest event") Instant latestActivity) {
    @Schema(description = "Minimal public event summary")
    public record PublicEventSummaryDTO(
            @Schema(description = "Database ID of the event") Long id,
            @Schema(description = "Sequence number for event ordering") Long sequenceNumber,
            @Schema(description = "Server reception timestamp") Instant serverReceived) {}
}
