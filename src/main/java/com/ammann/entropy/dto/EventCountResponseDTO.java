/* (C)2026 */
package com.ammann.entropy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Event count for a specific time window")
@JsonInclude(JsonInclude.Include.NON_NULL)
/**
 * Response payload containing an event count and its evaluation window.
 *
 * @param count number of events observed in the window
 * @param windowStart inclusive start of the counting window
 * @param windowEnd exclusive or logical end of the counting window
 * @param durationSeconds duration of the window in seconds
 */
public record EventCountResponseDTO(
        @Schema(description = "Total number of events in the time window") Long count,
        @Schema(description = "Start of the time window") Instant windowStart,
        @Schema(description = "End of the time window") Instant windowEnd,
        @Schema(description = "Duration of the window in seconds") Long durationSeconds) {}
