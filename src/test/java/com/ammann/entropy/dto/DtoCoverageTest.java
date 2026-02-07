/* (C)2026 */
package com.ammann.entropy.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DtoCoverageTest {
    @Test
    void eventRateResponseComputesValues() {
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = start.plusSeconds(10);

        EventRateResponseDTO dto = EventRateResponseDTO.create(100, start, end, 10.0, 5.0);

        assertThat(dto.averageRateHz()).isEqualTo(10.0);
        assertThat(dto.deviationPercent()).isEqualTo(0.0);
        assertThat(dto.withinExpectedRange()).isTrue();
        assertThat(dto.durationSeconds()).isEqualTo(10.0);
    }

    @Test
    void intervalStatisticsFromIntervalsHandlesEvenAndOdd() {
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = start.plusSeconds(1);

        IntervalStatisticsDTO odd =
                IntervalStatisticsDTO.fromIntervals(List.of(1L, 3L, 5L), start, end);
        assertThat(odd.count()).isEqualTo(3);
        assertThat(odd.meanNs()).isEqualTo(3.0);
        assertThat(odd.medianNs()).isEqualTo(3.0);

        IntervalStatisticsDTO even =
                IntervalStatisticsDTO.fromIntervals(List.of(1L, 2L, 3L, 4L), start, end);
        assertThat(even.count()).isEqualTo(4);
        assertThat(even.medianNs()).isEqualTo(2.5);
    }

    @Test
    void errorResponseConstructorsPopulateFields() {
        ErrorResponseDTO single = new ErrorResponseDTO("oops");
        assertThat(single.message()).isEqualTo("oops");
        assertThat(single.errorCode()).isNull();
        assertThat(single.timestamp()).isNotNull();

        ErrorResponseDTO withCode = new ErrorResponseDTO("boom", "E1");
        assertThat(withCode.message()).isEqualTo("boom");
        assertThat(withCode.errorCode()).isEqualTo("E1");
        assertThat(withCode.timestamp()).isNotNull();
    }

    @Test
    void nistSuiteResultRecommendationVariants() {
        TimeWindowDTO window = new TimeWindowDTO(Instant.now().minusSeconds(60), Instant.now(), 1L);

        NISTSuiteResultDTO passed =
                new NISTSuiteResultDTO(List.of(), 1, 1, 0, 1.0, true, Instant.now(), 100L, window);
        assertThat(passed.allTestsPassed()).isTrue();
        assertThat(passed.getRecommendation()).contains("meets NIST SP 800-22");

        NISTSuiteResultDTO minor =
                new NISTSuiteResultDTO(
                        List.of(), 10, 9, 1, 0.9, false, Instant.now(), 100L, window);
        assertThat(minor.allTestsPassed()).isFalse();
        assertThat(minor.getRecommendation()).contains("Minor randomness issues");

        NISTSuiteResultDTO critical =
                new NISTSuiteResultDTO(
                        List.of(), 10, 5, 5, 0.5, false, Instant.now(), 100L, window);
        assertThat(critical.getRecommendation()).contains("CRITICAL");
    }

    @Test
    void recordDtosExposeConstructorValues() {
        Instant now = Instant.parse("2024-01-01T00:00:00Z");
        Instant later = now.plusSeconds(5);

        ShannonEntropyResponseDTO shannon =
                new ShannonEntropyResponseDTO(1.23, 10L, now, later, 1_000);
        assertThat(shannon.shannonEntropy()).isEqualTo(1.23);
        assertThat(shannon.sampleCount()).isEqualTo(10L);

        RenyiEntropyResponseDTO renyi =
                new RenyiEntropyResponseDTO(2.34, 2.0, 10L, now, later, 1_000);
        assertThat(renyi.renyiEntropy()).isEqualTo(2.34);
        assertThat(renyi.alpha()).isEqualTo(2.0);

        EventCountResponseDTO count = new EventCountResponseDTO(5L, now, later, 5L);
        assertThat(count.count()).isEqualTo(5L);
        assertThat(count.durationSeconds()).isEqualTo(5L);

        RecentEventsResponseDTO.EventSummaryDTO summary =
                new RecentEventsResponseDTO.EventSummaryDTO(1L, 100L, 2L, now, 3L, 0.9, 50L);
        RecentEventsResponseDTO recent =
                new RecentEventsResponseDTO(List.of(summary), 1, now, later);
        assertThat(recent.events()).hasSize(1);
        assertThat(recent.events().get(0).intervalToPreviousNs()).isEqualTo(50L);
    }
}
