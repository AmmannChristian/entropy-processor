/* (C)2026 */
package com.ammann.entropy.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class Nist90BResultDTOTest {

    @Nested
    @DisplayName("Record Construction Tests")
    class ConstructionTests {

        @Test
        void canCreateWithAllFields() {
            Instant executed = Instant.parse("2024-01-01T01:00:00Z");
            TimeWindowDTO window =
                    new TimeWindowDTO(
                            Instant.parse("2024-01-01T00:00:00Z"),
                            Instant.parse("2024-01-01T01:00:00Z"),
                            1L);
            UUID assessmentRunId = UUID.randomUUID();

            NIST90BResultDTO dto =
                    new NIST90BResultDTO(
                            7.5, // minEntropy
                            true, // passed
                            "{\"estimator\": \"MCV\", \"value\": 7.5}",
                            executed,
                            1000000L,
                            window,
                            assessmentRunId);

            assertThat(dto.minEntropy()).isEqualTo(7.5);
            assertThat(dto.passed()).isTrue();
            assertThat(dto.assessmentDetails()).contains("MCV");
            assertThat(dto.executedAt()).isEqualTo(executed);
            assertThat(dto.bitsTested()).isEqualTo(1000000L);
            assertThat(dto.window()).isNotNull();
            assertThat(dto.assessmentRunId()).isEqualTo(assessmentRunId);
        }

        @Test
        void canCreateWithFailedAssessment() {
            Instant executed = Instant.now();
            TimeWindowDTO window =
                    new TimeWindowDTO(Instant.now().minusSeconds(3600), Instant.now(), 1L);
            UUID assessmentRunId = UUID.randomUUID();

            NIST90BResultDTO dto =
                    new NIST90BResultDTO(
                            3.5,
                            false,
                            "{\"reason\": \"entropy too low\"}",
                            executed,
                            500000L,
                            window,
                            assessmentRunId);

            assertThat(dto.passed()).isFalse();
            assertThat(dto.minEntropy()).isEqualTo(3.5);
            assertThat(dto.assessmentDetails()).contains("entropy too low");
        }

        @Test
        void canCreateWithNullDetails() {
            Instant executed = Instant.now();
            TimeWindowDTO window =
                    new TimeWindowDTO(Instant.now().minusSeconds(3600), Instant.now(), 1L);
            UUID assessmentRunId = UUID.randomUUID();

            NIST90BResultDTO dto =
                    new NIST90BResultDTO(
                            7.5, true, null, executed, 1000000L, window, assessmentRunId);

            assertThat(dto.assessmentDetails()).isNull();
            assertThat(dto.minEntropy()).isEqualTo(7.5);
        }
    }

    @Nested
    @DisplayName("Record Equality Tests")
    class EqualityTests {

        @Test
        void equalRecordsAreEqual() {
            Instant executed = Instant.parse("2024-01-01T01:00:00Z");
            TimeWindowDTO window =
                    new TimeWindowDTO(
                            Instant.parse("2024-01-01T00:00:00Z"),
                            Instant.parse("2024-01-01T01:00:00Z"),
                            1L);
            UUID assessmentRunId = UUID.randomUUID();

            NIST90BResultDTO dto1 =
                    new NIST90BResultDTO(
                            7.5, true, "details", executed, 1000000L, window, assessmentRunId);
            NIST90BResultDTO dto2 =
                    new NIST90BResultDTO(
                            7.5, true, "details", executed, 1000000L, window, assessmentRunId);

            assertThat(dto1).isEqualTo(dto2);
            assertThat(dto1.hashCode()).isEqualTo(dto2.hashCode());
        }

        @Test
        void differentPassedStatusMeansNotEqual() {
            Instant executed = Instant.parse("2024-01-01T01:00:00Z");
            TimeWindowDTO window =
                    new TimeWindowDTO(
                            Instant.parse("2024-01-01T00:00:00Z"),
                            Instant.parse("2024-01-01T01:00:00Z"),
                            1L);
            UUID assessmentRunId = UUID.randomUUID();

            NIST90BResultDTO dto1 =
                    new NIST90BResultDTO(
                            7.5, true, null, executed, 1000000L, window, assessmentRunId);
            NIST90BResultDTO dto2 =
                    new NIST90BResultDTO(
                            7.5, false, null, executed, 1000000L, window, assessmentRunId);

            assertThat(dto1).isNotEqualTo(dto2);
        }
    }

    @Nested
    @DisplayName("Min-Entropy Tests")
    class MinEntropyTests {

        @Test
        void minEntropyAccessible() {
            TimeWindowDTO window =
                    new TimeWindowDTO(Instant.now().minusSeconds(3600), Instant.now(), 1L);
            UUID assessmentRunId = UUID.randomUUID();

            NIST90BResultDTO dto =
                    new NIST90BResultDTO(
                            6.5, true, null, Instant.now(), 1000000L, window, assessmentRunId);

            assertThat(dto.minEntropy()).isEqualTo(6.5);
        }

        @Test
        void zeroEntropyValuesAllowed() {
            TimeWindowDTO window =
                    new TimeWindowDTO(Instant.now().minusSeconds(3600), Instant.now(), 1L);
            UUID assessmentRunId = UUID.randomUUID();

            NIST90BResultDTO dto =
                    new NIST90BResultDTO(
                            0.0, false, "all zeros", Instant.now(), 1000L, window, assessmentRunId);

            assertThat(dto.minEntropy()).isZero();
        }
    }
}
