package com.ammann.entropy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Clock drift analysis between Raspberry Pi and server time")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClockDriftInfoDTO(
        @Schema(description = "Clock drift rate in microseconds per hour")
        Double driftRateUsPerHour,

        @Schema(description = "Whether drift is significant (threshold: 10 microseconds per hour)")
        Boolean isSignificant,

        @Schema(description = "Recommendation for remediation")
        String recommendation
) {
    /**
     * Creates ClockDriftInfoDTO with standard recommendation based on drift rate.
     */
    public static ClockDriftInfoDTO create(double driftRateUsPerHour) {
        boolean significant = Math.abs(driftRateUsPerHour) > 10.0;
        String recommendation = significant
                ? "Check NTP synchronization on Raspberry Pi - drift exceeds threshold"
                : "Clock drift acceptable";

        return new ClockDriftInfoDTO(driftRateUsPerHour, significant, recommendation);
    }
}