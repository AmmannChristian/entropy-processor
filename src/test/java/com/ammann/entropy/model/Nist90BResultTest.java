/* (C)2026 */
package com.ammann.entropy.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.ammann.entropy.dto.NIST90BResultDTO;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class Nist90BResultTest {

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

            Nist90BResult result =
                    new Nist90BResult(
                            "batch-001",
                            7.5, // minEntropy
                            true, // passed
                            "{\"estimator\": \"MCV\"}",
                            1000000L, // bitsTested
                            start,
                            end);

            assertThat(result.batchId).isEqualTo("batch-001");
            assertThat(result.minEntropy).isEqualTo(7.5);
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

            Nist90BResult result =
                    new Nist90BResult(
                            "batch-fail",
                            3.5,
                            false,
                            "{\"reason\": \"low entropy\"}",
                            500000L,
                            start,
                            end);

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
            java.util.UUID runId = java.util.UUID.randomUUID();

            Nist90BResult result =
                    new Nist90BResult(
                            "batch-123", 7.5, true, "{\"test\": true}", 1000000L, start, end);
            result.assessmentRunId = runId;

            NIST90BResultDTO dto = result.toDTO();

            assertThat(dto.minEntropy()).isEqualTo(7.5);
            assertThat(dto.passed()).isTrue();
            assertThat(dto.assessmentDetails()).isEqualTo("{\"test\": true}");
            assertThat(dto.bitsTested()).isEqualTo(1000000L);
            assertThat(dto.executedAt()).isNotNull();
            assertThat(dto.window()).isNotNull();
            assertThat(dto.window().start()).isEqualTo(start);
            assertThat(dto.window().end()).isEqualTo(end);
            assertThat(dto.assessmentRunId()).isEqualTo(runId);
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
            result.bitsTested = null;

            NIST90BResultDTO dto = result.toDTO();

            assertThat(dto.minEntropy()).isEqualTo(0.0);
            assertThat(dto.bitsTested()).isEqualTo(0L);
        }

        @Test
        void calculatesWindowDurationCorrectly() {
            Instant start = Instant.parse("2024-01-01T00:00:00Z");
            Instant end = Instant.parse("2024-01-01T02:00:00Z"); // 2 hours later

            Nist90BResult result =
                    new Nist90BResult("batch-123", 7.5, true, null, 1000000L, start, end);

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
        void canSetMinEntropy() {
            Nist90BResult result = new Nist90BResult();
            result.minEntropy = 7.0;

            assertThat(result.minEntropy).isEqualTo(7.0);
        }

        @Test
        void executedAtDefaultsToNow() {
            Nist90BResult result = new Nist90BResult();
            assertThat(result.executedAt).isNotNull();
            assertThat(result.executedAt).isBeforeOrEqualTo(Instant.now());
        }

        @Test
        void sampleSizeMeetsNistMinimumDefaultsToNull() {
            Nist90BResult result = new Nist90BResult();
            assertThat(result.sampleSizeMeetsNistMinimum).isNull();
        }

        @Test
        void sampleSizeMeetsNistMinimumPropagatedToDTO() {
            Instant start = Instant.parse("2024-01-01T00:00:00Z");
            Instant end = Instant.parse("2024-01-01T01:00:00Z");

            Nist90BResult conformant =
                    new Nist90BResult("batch-1", 7.5, true, null, 8_000_000L, start, end);
            conformant.sampleSizeMeetsNistMinimum = true;
            conformant.assessmentScope = "NIST_SINGLE_SAMPLE";

            NIST90BResultDTO conformantDto = conformant.toDTO();
            assertThat(conformantDto.sampleSizeMeetsNistMinimum()).isTrue();
            assertThat(conformantDto.assessmentScope()).isEqualTo("NIST_SINGLE_SAMPLE");

            Nist90BResult undersized =
                    new Nist90BResult("batch-2", 6.0, true, null, 400_000L, start, end);
            undersized.sampleSizeMeetsNistMinimum = false;
            undersized.assessmentScope = "NIST_SINGLE_SAMPLE";

            NIST90BResultDTO undersizedDto = undersized.toDTO();
            assertThat(undersizedDto.sampleSizeMeetsNistMinimum()).isFalse();
            assertThat(undersizedDto.assessmentScope()).isEqualTo("NIST_SINGLE_SAMPLE");
        }

        @Test
        void sampleSizeMeetsNistMinimumNullForSummaryRows() {
            Instant start = Instant.parse("2024-01-01T00:00:00Z");
            Instant end = Instant.parse("2024-01-01T01:00:00Z");

            Nist90BResult summary =
                    new Nist90BResult("batch-1", 7.5, true, null, 8_000_000L, start, end);
            summary.isRunSummary = true;
            summary.assessmentScope = "PRODUCT_WINDOW_SUMMARY";
            // sampleSizeMeetsNistMinimum intentionally not set — summary rows don't represent a
            // single sample

            NIST90BResultDTO dto = summary.toDTO();
            assertThat(dto.sampleSizeMeetsNistMinimum()).isNull();
            assertThat(dto.assessmentScope()).isEqualTo("PRODUCT_WINDOW_SUMMARY");
        }
    }
}
