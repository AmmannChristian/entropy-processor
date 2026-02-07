package com.ammann.entropy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Event count for a specific time window")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventCountResponseDTO(
        @Schema(description = "Total number of events in the time window")
        Long count,

        @Schema(description = "Start of the time window")
        Instant windowStart,

        @Schema(description = "End of the time window")
        Instant windowEnd,

        @Schema(description = "Duration of the window in seconds")
        Long durationSeconds
) {}