/* (C)2026 */
package com.ammann.entropy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Public recent activity summary")
@JsonInclude(JsonInclude.Include.NON_NULL)
/**
 * Public-facing response for recent platform activity.
 *
 * <p>This DTO intentionally contains only non-sensitive metadata suitable for
 * unauthenticated clients.
 *
 * @param events recent event summaries with restricted fields
 * @param count number of returned summaries
 * @param latestActivity timestamp of the newest reported activity
 */
public record PublicActivityResponseDTO(
        @Schema(description = "List of recent events") List<PublicEventSummaryDTO> events,
        @Schema(description = "Number of events returned") Integer count,
        @Schema(description = "Server timestamp of newest event") Instant latestActivity) {
    /**
     * Minimal event projection exposed by public activity endpoints.
     *
     * @param id event identifier
     * @param sequenceNumber event sequence number
     * @param serverReceived server-side reception timestamp
     */
    @Schema(description = "Minimal public event summary")
    public record PublicEventSummaryDTO(
            @Schema(description = "Database ID of the event") Long id,
            @Schema(description = "Sequence number for event ordering") Long sequenceNumber,
            @Schema(description = "Server reception timestamp") Instant serverReceived) {}
}
