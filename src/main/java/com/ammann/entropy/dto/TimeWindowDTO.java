package com.ammann.entropy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;


@Schema(description = "Time window for data analysis")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TimeWindowDTO(
        @Schema(description = "Window start timestamp")
        Instant start,

        @Schema(description = "Window end timestamp")
        Instant end,

        @Schema(description = "Window duration in hours")
        Long durationHours
) {
    public static TimeWindowDTO create(Instant start, Instant end) {
        long durationHours = java.time.Duration.between(start, end).toHours();
        return new TimeWindowDTO(start, end, durationHours);
    }
}
