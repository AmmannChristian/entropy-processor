/* (C)2026 */
package com.ammann.entropy.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IntervalStatsTest {
    @Test
    void recordExposesFields() {
        IntervalStats stats = new IntervalStats(1, 2.0, 3.0, 4, 5, 6.0);

        assertThat(stats.count()).isEqualTo(1);
        assertThat(stats.meanNs()).isEqualTo(2.0);
        assertThat(stats.medianNs()).isEqualTo(6.0);
    }
}
