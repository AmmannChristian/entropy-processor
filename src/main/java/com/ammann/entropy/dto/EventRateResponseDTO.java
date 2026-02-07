package com.ammann.entropy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;

/**
 * Data transfer object for event rate statistics within a measurement window.
 *
 * <p>Reports the observed average event rate in Hz, compares it against the
 * expected detector count rate, and indicates whether the deviation falls
 * within an acceptable tolerance.
 */
@Schema(description = "Event rate statistics over time")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventRateResponseDTO(
        @Schema(description = "Average event rate in Hz (events per second)")
        Double averageRateHz,

        @Schema(description = "Expected detector count rate in Hz (configurable via entropy.source.expected-rate-hz)")
        Double expectedRateHz,

        @Schema(description = "Deviation from expected rate in percent")
        Double deviationPercent,

        @Schema(description = "Total events counted")
        Long totalEvents,

        @Schema(description = "Start of measurement window")
        Instant windowStart,

        @Schema(description = "End of measurement window")
        Instant windowEnd,

        @Schema(description = "Duration in seconds")
        Double durationSeconds,

        @Schema(description = "Whether the rate is within acceptable range (within configured deviation tolerance)")
        Boolean withinExpectedRange
) {
    /** Default deviation tolerance used when no explicit threshold is provided. */
    private static final double DEFAULT_ACCEPTABLE_DEVIATION_PERCENT = 20.0;

    /**
     * Creates an event rate response by computing the average rate, deviation,
     * and range check from the given event count and time window.
     *
     * @param totalEvents               number of events observed
     * @param start                     start of the measurement window
     * @param end                       end of the measurement window
     * @param expectedRateHz            expected detector count rate in Hz
     * @param acceptableDeviationPercent maximum acceptable deviation in percent
     * @return a fully computed {@code EventRateResponseDTO}
     */
    public static EventRateResponseDTO create(long totalEvents, Instant start, Instant end,
                                               double expectedRateHz, double acceptableDeviationPercent) {
        double durationSeconds = (end.toEpochMilli() - start.toEpochMilli()) / 1000.0;
        double averageRateHz = durationSeconds > 0 ? totalEvents / durationSeconds : 0;
        double deviationPercent = expectedRateHz > 0
                ? ((averageRateHz - expectedRateHz) / expectedRateHz) * 100.0
                : 0.0;
        boolean withinRange = Math.abs(deviationPercent) <= acceptableDeviationPercent;

        return new EventRateResponseDTO(
                averageRateHz,
                expectedRateHz,
                deviationPercent,
                totalEvents,
                start,
                end,
                durationSeconds,
                withinRange
        );
    }

    /**
     * Convenience overload using the default deviation tolerance of 20 percent.
     *
     * @param totalEvents  number of events observed
     * @param start        start of the measurement window
     * @param end          end of the measurement window
     * @param expectedRateHz expected detector count rate in Hz
     * @return a fully computed {@code EventRateResponseDTO}
     */
    public static EventRateResponseDTO create(long totalEvents, Instant start, Instant end,
                                               double expectedRateHz) {
        return create(totalEvents, start, end, expectedRateHz, DEFAULT_ACCEPTABLE_DEVIATION_PERCENT);
    }
}