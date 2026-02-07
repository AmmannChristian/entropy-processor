package com.ammann.entropy.model;

/**
 * Aggregated statistical summary of inter-event intervals computed via a single
 * database query. All temporal values are expressed in nanoseconds.
 *
 * @param count    number of positive intervals in the window
 * @param meanNs   arithmetic mean interval in nanoseconds
 * @param stdDevNs population standard deviation in nanoseconds
 * @param minNs    minimum observed interval in nanoseconds
 * @param maxNs    maximum observed interval in nanoseconds
 * @param medianNs median interval in nanoseconds (50th percentile)
 */
public record IntervalStats(
        long count,
        double meanNs,
        double stdDevNs,
        long minNs,
        long maxNs,
        double medianNs
) {}