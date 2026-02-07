package com.ammann.entropy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;

/**
 * Data transfer object for a Renyi entropy calculation result.
 *
 * <p>Contains the computed Renyi entropy in bits together with the alpha
 * parameter used, the sample count, the analysis time window, and the
 * histogram bucket size.
 */
@Schema(description = "Renyi entropy calculation result")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RenyiEntropyResponseDTO(
        @Schema(description = "Renyi entropy in bits")
        Double renyiEntropy,

        @Schema(description = "Alpha parameter used for calculation")
        Double alpha,

        @Schema(description = "Number of intervals analyzed")
        Long sampleCount,

        @Schema(description = "Start of analysis window")
        Instant windowStart,

        @Schema(description = "End of analysis window")
        Instant windowEnd,

        @Schema(description = "Histogram bucket size in nanoseconds")
        Integer bucketSizeNs
) {}