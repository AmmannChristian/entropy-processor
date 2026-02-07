/* (C)2026 */
package com.ammann.entropy.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntropyDataTest {

    @Test
    void validEventPassesValidation() {
        long nowNs = System.currentTimeMillis() * 1_000_000L;
        EntropyData data = new EntropyData("ts", nowNs, 1L);
        data.serverReceived = Instant.now();
        data.createdAt = Instant.now();

        assertThat(data.isValidForEntropy()).isTrue();
    }

    @Test
    void rejectsFutureAndPastSkew() {
        long nowNs = System.currentTimeMillis() * 1_000_000L;

        EntropyData future = new EntropyData("ts", nowNs + 120_000_000_000L, 1L); // +120s
        future.serverReceived = Instant.now();
        future.createdAt = Instant.now();
        assertThat(future.isValidForEntropy()).isFalse();

        EntropyData past =
                new EntropyData("ts", nowNs - 25L * 60 * 60 * 1_000_000_000L, 1L); // older than 24h
        past.serverReceived = Instant.now();
        past.createdAt = Instant.now();
        assertThat(past.isValidForEntropy()).isFalse();
    }

    @Test
    void rejectsOutOfRangeQualityScore() {
        long nowNs = System.currentTimeMillis() * 1_000_000L;
        EntropyData badQuality = new EntropyData("ts", nowNs, 1L);
        badQuality.serverReceived = Instant.now();
        badQuality.createdAt = Instant.now();
        badQuality.qualityScore = -0.1;

        assertThat(badQuality.isValidForEntropy()).isFalse();
    }

    @Test
    void entropyStatisticsHandleEmptyAndValues() {
        EntropyData.EntropyStatistics empty =
                EntropyData.EntropyStatistics.fromIntervals(List.of());
        assertThat(empty.meanInterval()).isZero();
        assertThat(empty.stdDeviation()).isZero();

        EntropyData.EntropyStatistics stats =
                EntropyData.EntropyStatistics.fromIntervals(List.of(1L, 3L, 5L));
        assertThat(stats.meanInterval()).isEqualTo(3.0);
        assertThat(stats.minInterval()).isEqualTo(1L);
        assertThat(stats.maxInterval()).isEqualTo(5L);
    }

    @Test
    void equalsHashCodeAndToStringAreStable() {
        EntropyData a = new EntropyData("ts", 123L, 1L);
        EntropyData b = new EntropyData("ts", 123L, 1L);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a.toString()).contains("EntropyData");
    }
}
