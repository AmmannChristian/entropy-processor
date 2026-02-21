/* (C)2026 */
package com.ammann.entropy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Time window for data analysis")
@JsonInclude(JsonInclude.Include.NON_NULL)
/**
 * Immutable representation of an analysis time window.
 *
 * <p>The duration is derived from the provided start and end timestamps and
 * expressed in whole hours for coarse-grained reporting.
 *
 * @param start window start timestamp
 * @param end window end timestamp
 * @param durationHours elapsed duration between start and end in hours
 */
public record TimeWindowDTO(
        @Schema(description = "Window start timestamp") Instant start,
        @Schema(description = "Window end timestamp") Instant end,
        @Schema(description = "Window duration in hours") Long durationHours) {
    /**
     * Creates a window DTO and computes duration in hours from the given bounds.
     *
     * @param start window start timestamp
     * @param end window end timestamp
     * @return populated time window DTO
     */
    public static TimeWindowDTO create(Instant start, Instant end) {
        long durationHours = java.time.Duration.between(start, end).toHours();
        return new TimeWindowDTO(start, end, durationHours);
    }
}
