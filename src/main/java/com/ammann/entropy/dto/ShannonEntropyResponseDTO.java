package com.ammann.entropy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;

/**
 * Data transfer object for a Shannon entropy calculation result.
 *
 * <p>Contains the computed Shannon entropy in bits together with the sample
 * count, the analysis time window, and the histogram bucket size used for
 * the probability distribution.
 */
@Schema(description = "Shannon entropy calculation result")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShannonEntropyResponseDTO(
        @Schema(description = "Shannon entropy in bits")
        Double shannonEntropy,

        @Schema(description = "Number of intervals analyzed")
        Long sampleCount,

        @Schema(description = "Start of analysis window")
        Instant windowStart,

        @Schema(description = "End of analysis window")
        Instant windowEnd,

        @Schema(description = "Histogram bucket size in nanoseconds")
        Integer bucketSizeNs
) {}