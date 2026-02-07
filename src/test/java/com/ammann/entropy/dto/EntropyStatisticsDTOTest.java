package com.ammann.entropy.dto;

import com.ammann.entropy.service.EntropyStatisticsService.BasicStatistics;
import com.ammann.entropy.service.EntropyStatisticsService.EntropyAnalysisResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EntropyStatisticsDTOTest {

    @Nested
    @DisplayName("Record Construction Tests")
    class ConstructionTests {

        @Test
        void canCreateWithAllFields() {
            Instant start = Instant.parse("2024-01-01T00:00:00Z");
            Instant end = Instant.parse("2024-01-01T01:00:00Z");
            BasicStatisticsDTO basicStats = new BasicStatisticsDTO(100L, 50000L, 100L, 2000L, 500.0, 250.0, 62500.0);

            EntropyStatisticsDTO dto = new EntropyStatisticsDTO(
                    7.5,   // shannonEntropy
                    6.8,   // renyiEntropy
                    0.95,  // sampleEntropy
                    0.85,  // approximateEntropy
                    1000L, // sampleCount
                    start,
                    end,
                    50000L, // processingTimeNs
                    basicStats
            );

            assertThat(dto.shannonEntropy()).isEqualTo(7.5);
            assertThat(dto.renyiEntropy()).isEqualTo(6.8);
            assertThat(dto.sampleEntropy()).isEqualTo(0.95);
            assertThat(dto.approximateEntropy()).isEqualTo(0.85);
            assertThat(dto.sampleCount()).isEqualTo(1000L);
            assertThat(dto.windowStart()).isEqualTo(start);
            assertThat(dto.windowEnd()).isEqualTo(end);
            assertThat(dto.processingTimeNs()).isEqualTo(50000L);
            assertThat(dto.basicStats()).isNotNull();
        }

        @Test
        void canCreateWithNullFields() {
            EntropyStatisticsDTO dto = new EntropyStatisticsDTO(
                    null, null, null, null, null, null, null, null, null
            );

            assertThat(dto.shannonEntropy()).isNull();
            assertThat(dto.renyiEntropy()).isNull();
            assertThat(dto.basicStats()).isNull();
        }
    }

    @Nested
    @DisplayName("from() Factory Method Tests")
    class FromMethodTests {

        @Test
        void convertsFromEntropyAnalysisResult() {
            Instant start = Instant.parse("2024-01-01T00:00:00Z");
            Instant end = Instant.parse("2024-01-01T01:00:00Z");

            BasicStatistics basicStats = new BasicStatistics(500L, 250000L, 100L, 2000L, 500.0, 250.0, 62500.0);

            EntropyAnalysisResult result = new EntropyAnalysisResult(
                    500,    // sampleCount
                    7.9,    // shannonEntropy
                    7.2,    // renyiEntropy
                    0.92,   // sampleEntropy
                    0.88,   // approximateEntropy
                    basicStats,
                    12345L  // processingTimeNanos
            );

            EntropyStatisticsDTO dto = EntropyStatisticsDTO.from(result, start, end);

            assertThat(dto.shannonEntropy()).isEqualTo(7.9);
            assertThat(dto.renyiEntropy()).isEqualTo(7.2);
            assertThat(dto.sampleEntropy()).isEqualTo(0.92);
            assertThat(dto.approximateEntropy()).isEqualTo(0.88);
            assertThat(dto.sampleCount()).isEqualTo(500L);
            assertThat(dto.windowStart()).isEqualTo(start);
            assertThat(dto.windowEnd()).isEqualTo(end);
            assertThat(dto.processingTimeNs()).isEqualTo(12345L);
            assertThat(dto.basicStats()).isNotNull();
            assertThat(dto.basicStats().mean()).isEqualTo(500.0);
        }
    }

    @Nested
    @DisplayName("Record Equality Tests")
    class EqualityTests {

        @Test
        void equalRecordsAreEqual() {
            Instant start = Instant.parse("2024-01-01T00:00:00Z");
            Instant end = Instant.parse("2024-01-01T01:00:00Z");

            EntropyStatisticsDTO dto1 = new EntropyStatisticsDTO(
                    7.5, 6.8, 0.95, 0.85, 1000L, start, end, 50000L, null
            );
            EntropyStatisticsDTO dto2 = new EntropyStatisticsDTO(
                    7.5, 6.8, 0.95, 0.85, 1000L, start, end, 50000L, null
            );

            assertThat(dto1).isEqualTo(dto2);
            assertThat(dto1.hashCode()).isEqualTo(dto2.hashCode());
        }

        @Test
        void differentRecordsAreNotEqual() {
            Instant start = Instant.parse("2024-01-01T00:00:00Z");
            Instant end = Instant.parse("2024-01-01T01:00:00Z");

            EntropyStatisticsDTO dto1 = new EntropyStatisticsDTO(
                    7.5, 6.8, 0.95, 0.85, 1000L, start, end, 50000L, null
            );
            EntropyStatisticsDTO dto2 = new EntropyStatisticsDTO(
                    8.0, 6.8, 0.95, 0.85, 1000L, start, end, 50000L, null
            );

            assertThat(dto1).isNotEqualTo(dto2);
        }
    }
}