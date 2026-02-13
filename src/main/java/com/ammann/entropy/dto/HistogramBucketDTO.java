package com.ammann.entropy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Represents a single bucket in an interval histogram with frequency count.
 *
 * <p>Each bucket corresponds to a range of interval values and contains the count
 * of intervals that fall within that range.
 */
@Schema(description = "Single histogram bucket with frequency count")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HistogramBucketDTO(
    @Schema(description = "Bucket start value (inclusive) in nanoseconds")
    Long bucketStartNs,

    @Schema(description = "Bucket end value (exclusive) in nanoseconds")
    Long bucketEndNs,

    @Schema(description = "Bucket center value in nanoseconds")
    Long bucketCenterNs,

    @Schema(description = "Number of intervals in this bucket")
    Integer count,

    @Schema(description = "Relative frequency (count / total)")
    Double frequency
) {}
