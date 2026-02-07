package com.ammann.entropy.model;

import com.ammann.entropy.dto.NIST90BResultDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class Nist90BResultTest
{

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        void defaultConstructorCreatesInstance() {
            Nist90BResult result = new Nist90BResult();
            assertThat(result).isNotNull();
        }

        @Test
        void parameterizedConstructorSetsAllFields() {
            Instant start = Instant.parse("2024-01-01T00:00:00Z");
            Instant end = Instant.parse("2024-01-01T01:00:00Z");

            Nist90BResult result = new Nist90BResult(
                    "batch-001",
                    7.5,      // minEntropy
                    7.8,      // shannonEntropy
                    7.2,      // collisionEntropy
                    7.1,      // markovEntropy
                    7.3,      // compressionEntropy
                    true,     // passed
                    "{\"estimator\": \"MCV\"}",
                    1000000L, // bitsTested
                    start,
                    end
            );

            assertThat(result.batchId).isEqualTo("batch-001");
            assertThat(result.minEntropy).isEqualTo(7.5);
            assertThat(result.shannonEntropy).isEqualTo(7.8);
            assertThat(result.collisionEntropy).isEqualTo(7.2);
            assertThat(result.markovEntropy).isEqualTo(7.1);
            assertThat(result.compressionEntropy).isEqualTo(7.3);
            assertThat(result.passed).isTrue();
            assertThat(result.assessmentDetails).isEqualTo("{\"estimator\": \"MCV\"}");
            assertThat(result.bitsTested).isEqualTo(1000000L);
            assertThat(result.windowStart).isEqualTo(start);
            assertThat(result.windowEnd).isEqualTo(end);
            assertThat(result.executedAt).isNotNull();
        }

        @Test
        void constructorHandlesFailedAssessment() {
            Instant start = Instant.now().minusSeconds(3600);
            Instant end = Instant.now();

            Nist90BResult result = new Nist90BResult(
                    "batch-fail",
                    3.5, 4.0, 3.8, 3.2, 3.9,
                    false,
                    "{\"reason\": \"low entropy\"}",
                    500000L,
                    start, end
            );

            assertThat(result.passed).isFalse();
            assertThat(result.minEntropy).isEqualTo(3.5);
        }
    }

    @Nested
    @DisplayName("toDTO Tests")
    class ToDtoTests {

        @Test
        void convertsToDtoWithAllFields() {
            Instant start = Instant.parse("2024-01-01T00:00:00Z");
            Instant end = Instant.parse("2024-01-01T01:00:00Z");

            Nist90BResult result = new Nist90BResult(
                    "batch-123",
                    7.5, 7.8, 7.2, 7.1, 7.3,
                    true,
                    "{\"test\": true}",
                    1000000L,
                    start, end
            );

            NIST90BResultDTO dto = result.toDTO();

            assertThat(dto.minEntropy()).isEqualTo(7.5);
            assertThat(dto.shannonEntropy()).isEqualTo(7.8);
            assertThat(dto.collisionEntropy()).isEqualTo(7.2);
            assertThat(dto.markovEntropy()).isEqualTo(7.1);
            assertThat(dto.compressionEntropy()).isEqualTo(7.3);
            assertThat(dto.passed()).isTrue();
            assertThat(dto.assessmentDetails()).isEqualTo("{\"test\": true}");
            assertThat(dto.bitsTested()).isEqualTo(1000000L);
            assertThat(dto.executedAt()).isNotNull();
            assertThat(dto.window()).isNotNull();
            assertThat(dto.window().start()).isEqualTo(start);
            assertThat(dto.window().end()).isEqualTo(end);
        }

        @Test
        void handlesNullEntropyValues() {
            Instant start = Instant.now().minusSeconds(3600);
            Instant end = Instant.now();

            Nist90BResult result = new Nist90BResult();
            result.windowStart = start;
            result.windowEnd = end;
            result.passed = true;
            result.minEntropy = null;
            result.shannonEntropy = null;
            result.collisionEntropy = null;
            result.markovEntropy = null;
            result.compressionEntropy = null;
            result.bitsTested = null;

            NIST90BResultDTO dto = result.toDTO();

            assertThat(dto.minEntropy()).isEqualTo(0.0);
            assertThat(dto.shannonEntropy()).isEqualTo(0.0);
            assertThat(dto.collisionEntropy()).isEqualTo(0.0);
            assertThat(dto.markovEntropy()).isEqualTo(0.0);
            assertThat(dto.compressionEntropy()).isEqualTo(0.0);
            assertThat(dto.bitsTested()).isEqualTo(0L);
        }

        @Test
        void calculatesWindowDurationCorrectly() {
            Instant start = Instant.parse("2024-01-01T00:00:00Z");
            Instant end = Instant.parse("2024-01-01T02:00:00Z"); // 2 hours later

            Nist90BResult result = new Nist90BResult(
                    "batch-123",
                    7.5, 7.8, 7.2, 7.1, 7.3,
                    true, null, 1000000L,
                    start, end
            );

            NIST90BResultDTO dto = result.toDTO();

            assertThat(dto.window().durationHours()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Constants Tests")
    class ConstantsTests {

        @Test
        void tableNameConstant() {
            assertThat(Nist90BResult.TABLE_NAME).isEqualTo("nist_90b_results");
        }
    }

    @Nested
    @DisplayName("Field Tests")
    class FieldTests {

        @Test
        void canSetAllEntropyTypes() {
            Nist90BResult result = new Nist90BResult();
            result.minEntropy = 7.0;
            result.shannonEntropy = 7.5;
            result.collisionEntropy = 6.8;
            result.markovEntropy = 6.5;
            result.compressionEntropy = 7.2;

            assertThat(result.minEntropy).isEqualTo(7.0);
            assertThat(result.shannonEntropy).isEqualTo(7.5);
            assertThat(result.collisionEntropy).isEqualTo(6.8);
            assertThat(result.markovEntropy).isEqualTo(6.5);
            assertThat(result.compressionEntropy).isEqualTo(7.2);
        }

        @Test
        void executedAtDefaultsToNow() {
            Nist90BResult result = new Nist90BResult();
            assertThat(result.executedAt).isNotNull();
            assertThat(result.executedAt).isBeforeOrEqualTo(Instant.now());
        }
    }
}