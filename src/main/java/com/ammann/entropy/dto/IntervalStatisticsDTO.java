package com.ammann.entropy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "Statistical analysis of intervals between decay events")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntervalStatisticsDTO(
        @Schema(description = "Number of intervals analyzed")
        Long count,

        @Schema(description = "Mean interval in nanoseconds")
        Double meanNs,

        @Schema(description = "Standard deviation in nanoseconds")
        Double stdDevNs,

        @Schema(description = "Minimum interval in nanoseconds")
        Long minNs,

        @Schema(description = "Maximum interval in nanoseconds")
        Long maxNs,

        @Schema(description = "Median interval in nanoseconds")
        Double medianNs,

        @Schema(description = "Coefficient of variation (stdDev/mean)")
        Double coefficientOfVariation,

        @Schema(description = "Start of analysis window")
        Instant windowStart,

        @Schema(description = "End of analysis window")
        Instant windowEnd
) {
    public static IntervalStatisticsDTO fromIntervals(List<Long> intervals, Instant start, Instant end) {
        if (intervals == null || intervals.isEmpty()) {
            return new IntervalStatisticsDTO(0L, 0.0, 0.0, 0L, 0L, 0.0, 0.0, start, end);
        }

        long count = intervals.size();
        double meanNs = intervals.stream().mapToLong(Long::longValue).average().orElse(0.0);
        long minNs = intervals.stream().mapToLong(Long::longValue).min().orElse(0);
        long maxNs = intervals.stream().mapToLong(Long::longValue).max().orElse(0);

        double variance = intervals.stream()
                .mapToDouble(x -> Math.pow(x - meanNs, 2))
                .average()
                .orElse(0.0);
        double stdDevNs = Math.sqrt(variance);

        List<Long> sorted = intervals.stream().sorted().toList();
        double medianNs;
        int size = sorted.size();
        if (size % 2 == 0) {
            medianNs = (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
        } else {
            medianNs = sorted.get(size / 2);
        }

        double cv = meanNs > 0 ? stdDevNs / meanNs : 0.0;

        return new IntervalStatisticsDTO(count, meanNs, stdDevNs, minNs, maxNs, medianNs, cv, start, end);
    }
}