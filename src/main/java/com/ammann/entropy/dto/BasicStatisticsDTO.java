package com.ammann.entropy.dto;

import com.ammann.entropy.service.EntropyStatisticsService;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Data transfer object carrying basic descriptive statistics computed over
 * inter-event intervals from the entropy source.
 *
 * <p>All temporal values (sum, min, max, mean, standard deviation) are expressed
 * in nanoseconds, consistent with the hardware timestamp resolution of the TDC.
 */
@Schema(description = "Basic statistical measures of interval data between decay events")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BasicStatisticsDTO(
        @Schema(description = "Number of data points analyzed")
        Long count,

        @Schema(description = "Sum of all intervals in nanoseconds")
        Long sum,

        @Schema(description = "Minimum interval observed in nanoseconds")
        Long min,

        @Schema(description = "Maximum interval observed in nanoseconds")
        Long max,

        @Schema(description = "Mean interval in nanoseconds")
        Double mean,

        @Schema(description = "Standard deviation in nanoseconds")
        Double standardDeviation,

        @Schema(description = "Variance in nanoseconds squared")
        Double variance
) {
    /**
     * Creates a DTO from the service-layer statistics record.
     *
     * @param stats the computed basic statistics from {@link EntropyStatisticsService}
     * @return a new {@code BasicStatisticsDTO} populated with the given values
     */
    static BasicStatisticsDTO from(EntropyStatisticsService.BasicStatistics stats) {
        return new BasicStatisticsDTO(
                stats.count(),
                stats.sum(),
                stats.min(),
                stats.max(),
                stats.mean(),
                stats.standardDeviation(),
                stats.variance()
        );
    }
}