package com.ammann.entropy.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class Nist90BResultDTOTest
{

    @Nested
    @DisplayName("Record Construction Tests")
    class ConstructionTests {

        @Test
        void canCreateWithAllFields() {
            Instant executed = Instant.parse("2024-01-01T01:00:00Z");
            TimeWindowDTO window = new TimeWindowDTO(
                    Instant.parse("2024-01-01T00:00:00Z"),
                    Instant.parse("2024-01-01T01:00:00Z"),
                    1L
            );

            NIST90BResultDTO dto = new NIST90BResultDTO(
                    7.5,    // minEntropy
                    7.8,    // shannonEntropy
                    7.2,    // collisionEntropy
                    7.1,    // markovEntropy
                    7.3,    // compressionEntropy
                    true,   // passed
                    "{\"estimator\": \"MCV\", \"value\": 7.5}",
                    executed,
                    1000000L,
                    window
            );

            assertThat(dto.minEntropy()).isEqualTo(7.5);
            assertThat(dto.shannonEntropy()).isEqualTo(7.8);
            assertThat(dto.collisionEntropy()).isEqualTo(7.2);
            assertThat(dto.markovEntropy()).isEqualTo(7.1);
            assertThat(dto.compressionEntropy()).isEqualTo(7.3);
            assertThat(dto.passed()).isTrue();
            assertThat(dto.assessmentDetails()).contains("MCV");
            assertThat(dto.executedAt()).isEqualTo(executed);
            assertThat(dto.bitsTested()).isEqualTo(1000000L);
            assertThat(dto.window()).isNotNull();
        }

        @Test
        void canCreateWithFailedAssessment() {
            Instant executed = Instant.now();
            TimeWindowDTO window = new TimeWindowDTO(
                    Instant.now().minusSeconds(3600),
                    Instant.now(),
                    1L
            );

            NIST90BResultDTO dto = new NIST90BResultDTO(
                    3.5, 4.0, 3.8, 3.2, 3.9,
                    false,
                    "{\"reason\": \"entropy too low\"}",
                    executed,
                    500000L,
                    window
            );

            assertThat(dto.passed()).isFalse();
            assertThat(dto.minEntropy()).isEqualTo(3.5);
            assertThat(dto.assessmentDetails()).contains("entropy too low");
        }

        @Test
        void canCreateWithNullDetails() {
            Instant executed = Instant.now();
            TimeWindowDTO window = new TimeWindowDTO(
                    Instant.now().minusSeconds(3600),
                    Instant.now(),
                    1L
            );

            NIST90BResultDTO dto = new NIST90BResultDTO(
                    7.5, 7.8, 7.2, 7.1, 7.3,
                    true,
                    null,
                    executed,
                    1000000L,
                    window
            );

            assertThat(dto.assessmentDetails()).isNull();
        }
    }

    @Nested
    @DisplayName("Record Equality Tests")
    class EqualityTests {

        @Test
        void equalRecordsAreEqual() {
            Instant executed = Instant.parse("2024-01-01T01:00:00Z");
            TimeWindowDTO window = new TimeWindowDTO(
                    Instant.parse("2024-01-01T00:00:00Z"),
                    Instant.parse("2024-01-01T01:00:00Z"),
                    1L
            );

            NIST90BResultDTO dto1 = new NIST90BResultDTO(
                    7.5, 7.8, 7.2, 7.1, 7.3, true, "details", executed, 1000000L, window
            );
            NIST90BResultDTO dto2 = new NIST90BResultDTO(
                    7.5, 7.8, 7.2, 7.1, 7.3, true, "details", executed, 1000000L, window
            );

            assertThat(dto1).isEqualTo(dto2);
            assertThat(dto1.hashCode()).isEqualTo(dto2.hashCode());
        }

        @Test
        void differentPassedStatusMeansNotEqual() {
            Instant executed = Instant.parse("2024-01-01T01:00:00Z");
            TimeWindowDTO window = new TimeWindowDTO(
                    Instant.parse("2024-01-01T00:00:00Z"),
                    Instant.parse("2024-01-01T01:00:00Z"),
                    1L
            );

            NIST90BResultDTO dto1 = new NIST90BResultDTO(
                    7.5, 7.8, 7.2, 7.1, 7.3, true, null, executed, 1000000L, window
            );
            NIST90BResultDTO dto2 = new NIST90BResultDTO(
                    7.5, 7.8, 7.2, 7.1, 7.3, false, null, executed, 1000000L, window
            );

            assertThat(dto1).isNotEqualTo(dto2);
        }
    }

    @Nested
    @DisplayName("Entropy Values Tests")
    class EntropyValuesTests {

        @Test
        void allEntropyTypesAccessible() {
            TimeWindowDTO window = new TimeWindowDTO(
                    Instant.now().minusSeconds(3600),
                    Instant.now(),
                    1L
            );

            NIST90BResultDTO dto = new NIST90BResultDTO(
                    6.5, 7.0, 6.8, 6.2, 6.9,
                    true, null, Instant.now(), 1000000L, window
            );

            assertThat(dto.minEntropy()).isLessThanOrEqualTo(dto.shannonEntropy());
            assertThat(dto.collisionEntropy()).isPositive();
            assertThat(dto.markovEntropy()).isPositive();
            assertThat(dto.compressionEntropy()).isPositive();
        }

        @Test
        void zeroEntropyValuesAllowed() {
            TimeWindowDTO window = new TimeWindowDTO(
                    Instant.now().minusSeconds(3600),
                    Instant.now(),
                    1L
            );

            NIST90BResultDTO dto = new NIST90BResultDTO(
                    0.0, 0.0, 0.0, 0.0, 0.0,
                    false, "all zeros", Instant.now(), 1000L, window
            );

            assertThat(dto.minEntropy()).isZero();
            assertThat(dto.shannonEntropy()).isZero();
        }
    }
}