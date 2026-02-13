package com.ammann.entropy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Response DTO containing histogram of interval frequencies between decay events.
 *
 * <p>Provides a complete view of the interval distribution including bucket frequencies,
 * time window, and summary statistics about the intervals analyzed.
 */
@Schema(description = "Histogram of interval frequencies between decay events")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntervalHistogramDTO(
    @Schema(description = "List of histogram buckets with frequencies")
    List<HistogramBucketDTO> buckets,

    @Schema(description = "Total number of intervals analyzed")
    Long totalIntervals,

    @Schema(description = "Bucket size in nanoseconds")
    Integer bucketSizeNs,

    @Schema(description = "Start of time window")
    Instant windowStart,

    @Schema(description = "End of time window")
    Instant windowEnd,

    @Schema(description = "Minimum interval in dataset (ns)")
    Long minIntervalNs,

    @Schema(description = "Maximum interval in dataset (ns)")
    Long maxIntervalNs
) {
    /**
     * Creates an IntervalHistogramDTO from a raw histogram map and interval data.
     *
     * @param histogram the histogram map from bucket start values to frequencies
     * @param intervals the list of raw interval values
     * @param bucketSizeNs the bucket size in nanoseconds
     * @param windowStart the start of the time window
     * @param windowEnd the end of the time window
     * @return a complete histogram DTO
     */
    public static IntervalHistogramDTO from(
        Map<Long, Integer> histogram,
        List<Long> intervals,
        int bucketSizeNs,
        Instant windowStart,
        Instant windowEnd
    ) {
        long total = intervals.size();
        long min = intervals.stream().mapToLong(Long::longValue).min().orElse(0L);
        long max = intervals.stream().mapToLong(Long::longValue).max().orElse(0L);

        List<HistogramBucketDTO> buckets = histogram.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> new HistogramBucketDTO(
                e.getKey(),
                e.getKey() + bucketSizeNs,
                e.getKey() + bucketSizeNs / 2,
                e.getValue(),
                e.getValue() / (double) total
            ))
            .collect(Collectors.toList());

        return new IntervalHistogramDTO(
            buckets, total, bucketSizeNs,
            windowStart, windowEnd, min, max
        );
    }
}
