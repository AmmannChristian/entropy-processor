package com.ammann.entropy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Data transfer object for radioactive decay rate plausibility validation.
 *
 * <p>Compares the observed average inter-event interval against the expected
 * interval derived from the configured detector count rate. The acceptable
 * range is bounded by configurable minimum and maximum thresholds.
 */
@Schema(description = "Radioactive decay rate validation for entropy source plausibility")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DecayRateInfoDTO(
        @Schema(description = "Observed average interval in milliseconds")
        Double averageIntervalMs,

        @Schema(description = "Whether decay rate is realistic for entropy source")
        Boolean isRealistic,

        @Schema(description = "Expected interval range for 1 microCurie entropy source in milliseconds")
        String expectedRange,

        @Schema(description = "Deviation from expected rate in percent")
        Double deviationPercent
) {
    /**
     * Creates DecayRateInfoDTO with configurable expected values.
     * The expected interval and acceptable range should be derived from the
     * configured detector count rate to ensure consistency.
     *
     * @param averageIntervalMs  Observed average interval in ms
     * @param expectedIntervalMs Expected average interval in ms (derived from configured rate)
     * @param minAcceptableMs    Minimum acceptable interval in ms
     * @param maxAcceptableMs    Maximum acceptable interval in ms
     * @return DecayRateInfoDTO with plausibility assessment
     */
    public static DecayRateInfoDTO create(double averageIntervalMs,
                                           double expectedIntervalMs,
                                           double minAcceptableMs,
                                           double maxAcceptableMs) {
        boolean realistic = averageIntervalMs >= minAcceptableMs && averageIntervalMs <= maxAcceptableMs;
        double deviation = expectedIntervalMs > 0
                ? Math.abs((averageIntervalMs - expectedIntervalMs) / expectedIntervalMs * 100.0)
                : 0.0;

        return new DecayRateInfoDTO(
                averageIntervalMs,
                realistic,
                String.format("%.1f-%.1f ms", minAcceptableMs, maxAcceptableMs),
                deviation
        );
    }
}