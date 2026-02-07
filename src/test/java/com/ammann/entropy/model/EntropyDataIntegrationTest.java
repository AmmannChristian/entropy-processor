/* (C)2026 */
package com.ammann.entropy.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.ammann.entropy.support.TestDataFactory;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class EntropyDataIntegrationTest {

    @Test
    @TestTransaction
    void calculateIntervalsFiltersNonPositive() {
        EntropyData.deleteAll();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");

        EntropyData.persist(
                TestDataFactory.createEntropyEvent(1, 1_000L, base.plusSeconds(1)),
                TestDataFactory.createEntropyEvent(2, 1_000L, base.plusSeconds(2)), // zero interval
                TestDataFactory.createEntropyEvent(3, 2_000L, base.plusSeconds(3)));

        List<Long> intervals =
                EntropyData.calculateIntervals(base.minusSeconds(1), base.plusSeconds(10));

        assertThat(intervals).containsExactly(1_000L);
    }

    @Test
    @TestTransaction
    void calculateIntervalStatsReturnsZerosWhenNoIntervals() {
        EntropyData.deleteAll();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");

        EntropyData.persist(TestDataFactory.createEntropyEvent(1, 1_000L, base.plusSeconds(1)));

        IntervalStats stats =
                EntropyData.calculateIntervalStats(base.minusSeconds(1), base.plusSeconds(10));

        assertThat(stats.count()).isZero();
        assertThat(stats.meanNs()).isZero();
        assertThat(stats.minNs()).isZero();
        assertThat(stats.maxNs()).isZero();
        assertThat(stats.medianNs()).isZero();
    }

    @Test
    @TestTransaction
    void getIntervalToPreviousAndRecentStatistics() {
        EntropyData.deleteAll();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");

        EntropyData e1 = TestDataFactory.createEntropyEvent(1, 1_000L, base.plusSeconds(1));
        EntropyData e2 = TestDataFactory.createEntropyEvent(2, 2_500L, base.plusSeconds(2));
        EntropyData e3 = TestDataFactory.createEntropyEvent(3, 4_000L, base.plusSeconds(3));
        EntropyData.persist(e1, e2, e3);

        assertThat(e1.getIntervalToPrevious()).isNull();
        assertThat(e2.getIntervalToPrevious()).isEqualTo(1_500L);

        EntropyData.EntropyStatistics stats = EntropyData.getRecentStatistics(3);
        assertThat(stats.meanInterval()).isEqualTo(1_500.0);
        assertThat(stats.minInterval()).isEqualTo(1_500L);
        assertThat(stats.maxInterval()).isEqualTo(1_500L);
    }

    @Test
    @TestTransaction
    void getRecentStatisticsReturnsZeroWhenInsufficientEvents() {
        EntropyData.deleteAll();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        EntropyData.persist(TestDataFactory.createEntropyEvent(1, 1_000L, base.plusSeconds(1)));

        EntropyData.EntropyStatistics stats = EntropyData.getRecentStatistics(1);

        assertThat(stats.meanInterval()).isZero();
        assertThat(stats.stdDeviation()).isZero();
    }
}
