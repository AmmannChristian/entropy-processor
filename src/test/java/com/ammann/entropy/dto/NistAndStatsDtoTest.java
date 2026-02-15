/* (C)2026 */
package com.ammann.entropy.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.ammann.entropy.service.EntropyStatisticsService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class NistAndStatsDtoTest {

    @Test
    void nistSuiteRecommendationCoversAllBranches() {
        NISTSuiteResultDTO ok =
                new NISTSuiteResultDTO(List.of(), 1, 1, 0, 1.0, true, Instant.now(), 1024L, null);
        assertThat(ok.allTestsPassed()).isTrue();
        assertThat(ok.getRecommendation()).contains("meets");

        NISTSuiteResultDTO minor =
                new NISTSuiteResultDTO(List.of(), 10, 8, 2, 0.8, false, Instant.now(), 1024L, null);
        assertThat(minor.allTestsPassed()).isFalse();
        assertThat(minor.getRecommendation()).contains("Minor randomness issues");

        NISTSuiteResultDTO critical =
                new NISTSuiteResultDTO(List.of(), 10, 5, 5, 0.5, false, Instant.now(), 1024L, null);
        assertThat(critical.getRecommendation()).contains("CRITICAL");
    }

    @Test
    void nistTestResultFactoriesPopulateFields() {
        NISTTestResultDTO pass = NISTTestResultDTO.create("Frequency", true, 0.5);
        assertThat(pass.status()).isEqualTo("PASS");
        assertThat(pass.passed()).isTrue();
        assertThat(pass.pValue()).isCloseTo(0.5, within(1e-6));
        assertThat(pass.executedAt()).isNotNull();

        NISTTestResultDTO error = NISTTestResultDTO.error("Runs", "boom");
        assertThat(error.status()).isEqualTo("ERROR");
        assertThat(error.passed()).isFalse();
        assertThat(error.details()).isEqualTo("boom");
    }

    @Test
    void clockDriftCreateSetsRecommendationAndSignificance() {
        ClockDriftInfoDTO small = ClockDriftInfoDTO.create(5.0);
        assertThat(small.isSignificant()).isFalse();
        assertThat(small.recommendation()).contains("acceptable");

        ClockDriftInfoDTO big = ClockDriftInfoDTO.create(25.0);
        assertThat(big.isSignificant()).isTrue();
        assertThat(big.recommendation()).contains("Check edge gateway time synchronization");
    }

    @Test
    void timeWindowCreateCalculatesDuration() {
        Instant start = Instant.now().minusSeconds(7200);
        Instant end = Instant.now();

        TimeWindowDTO window = TimeWindowDTO.create(start, end);
        assertThat(window.durationHours()).isEqualTo(2);
        assertThat(window.start()).isEqualTo(start);
        assertThat(window.end()).isEqualTo(end);
    }

    @Test
    void basicStatisticsDtoMapsFromServiceRecord() {
        EntropyStatisticsService.BasicStatistics stats =
                new EntropyStatisticsService.BasicStatistics(3L, 15L, 1L, 10L, 5.0, 2.0, 4.0);

        BasicStatisticsDTO dto = BasicStatisticsDTO.from(stats);

        assertThat(dto.count()).isEqualTo(3L);
        assertThat(dto.mean()).isEqualTo(5.0);
        assertThat(dto.standardDeviation()).isEqualTo(2.0);
        assertThat(dto.variance()).isEqualTo(4.0);
    }
}
