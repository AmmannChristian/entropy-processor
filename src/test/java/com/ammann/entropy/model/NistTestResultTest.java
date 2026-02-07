package com.ammann.entropy.model;

import com.ammann.entropy.dto.NISTTestResultDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NistTestResultTest
{

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        void defaultConstructorCreatesInstance() {
            NistTestResult result = new NistTestResult();
            assertThat(result).isNotNull();
        }

        @Test
        void parameterizedConstructorSetsAllFields() {
            UUID runId = UUID.randomUUID();
            Instant start = Instant.parse("2024-01-01T00:00:00Z");
            Instant end = Instant.parse("2024-01-01T01:00:00Z");

            NistTestResult result = new NistTestResult(runId, "Frequency Test", true, 0.95, start, end);

            assertThat(result.testSuiteRunId).isEqualTo(runId);
            assertThat(result.testName).isEqualTo("Frequency Test");
            assertThat(result.passed).isTrue();
            assertThat(result.pValue).isEqualTo(0.95);
            assertThat(result.windowStart).isEqualTo(start);
            assertThat(result.windowEnd).isEqualTo(end);
            assertThat(result.executedAt).isNotNull();
        }

        @Test
        void constructorHandlesFailedTest() {
            UUID runId = UUID.randomUUID();
            Instant start = Instant.now().minusSeconds(3600);
            Instant end = Instant.now();

            NistTestResult result = new NistTestResult(runId, "Runs Test", false, 0.005, start, end);

            assertThat(result.passed).isFalse();
            assertThat(result.pValue).isEqualTo(0.005);
        }

        @Test
        void constructorHandlesNullPValue() {
            UUID runId = UUID.randomUUID();
            Instant start = Instant.now().minusSeconds(3600);
            Instant end = Instant.now();

            NistTestResult result = new NistTestResult(runId, "Test", true, null, start, end);

            assertThat(result.pValue).isNull();
        }
    }

    @Nested
    @DisplayName("toDTO Tests")
    class ToDtoTests {

        @Test
        void convertsToDtoWithPassStatus() {
            UUID runId = UUID.randomUUID();
            Instant start = Instant.parse("2024-01-01T00:00:00Z");
            Instant end = Instant.parse("2024-01-01T01:00:00Z");

            NistTestResult result = new NistTestResult(runId, "Frequency (Monobit) Test", true, 0.85, start, end);
            result.details = "{\"statistic\": 1.23}";

            NISTTestResultDTO dto = result.toDTO();

            assertThat(dto.testName()).isEqualTo("Frequency (Monobit) Test");
            assertThat(dto.passed()).isTrue();
            assertThat(dto.pValue()).isEqualTo(0.85);
            assertThat(dto.status()).isEqualTo("PASS");
            assertThat(dto.executedAt()).isNotNull();
            assertThat(dto.details()).isEqualTo("{\"statistic\": 1.23}");
        }

        @Test
        void convertsToDtoWithFailStatus() {
            UUID runId = UUID.randomUUID();
            Instant start = Instant.now().minusSeconds(3600);
            Instant end = Instant.now();

            NistTestResult result = new NistTestResult(runId, "Random Excursions Test", false, 0.001, start, end);

            NISTTestResultDTO dto = result.toDTO();

            assertThat(dto.passed()).isFalse();
            assertThat(dto.status()).isEqualTo("FAIL");
        }

        @Test
        void handlesNullDetails() {
            UUID runId = UUID.randomUUID();
            Instant start = Instant.now().minusSeconds(3600);
            Instant end = Instant.now();

            NistTestResult result = new NistTestResult(runId, "Test", true, 0.5, start, end);
            result.details = null;

            NISTTestResultDTO dto = result.toDTO();

            assertThat(dto.details()).isNull();
        }
    }

    @Nested
    @DisplayName("toString Tests")
    class ToStringTests {

        @Test
        void formatsCorrectlyForPassedTest() {
            UUID runId = UUID.randomUUID();
            Instant start = Instant.now().minusSeconds(3600);
            Instant end = Instant.now();

            NistTestResult result = new NistTestResult(runId, "Frequency Test", true, 0.9876, start, end);

            String str = result.toString();

            assertThat(str).contains("NISTTestResult");
            assertThat(str).contains("test='Frequency Test'");
            assertThat(str).contains("passed=true");
            assertThat(str).contains("pValue=0.9876");
            assertThat(str).contains(runId.toString());
        }

        @Test
        void formatsCorrectlyForFailedTest() {
            UUID runId = UUID.randomUUID();
            Instant start = Instant.now().minusSeconds(3600);
            Instant end = Instant.now();

            NistTestResult result = new NistTestResult(runId, "Runs Test", false, 0.0012, start, end);

            String str = result.toString();

            assertThat(str).contains("passed=false");
            assertThat(str).contains("pValue=0.0012");
        }
    }

    @Nested
    @DisplayName("Field Tests")
    class FieldTests {

        @Test
        void canSetOptionalFields() {
            NistTestResult result = new NistTestResult();
            result.batchId = "batch-123";
            result.dataSampleSize = 1000000L;
            result.bitsTested = 999000L;
            result.details = "{\"key\": \"value\"}";

            assertThat(result.batchId).isEqualTo("batch-123");
            assertThat(result.dataSampleSize).isEqualTo(1000000L);
            assertThat(result.bitsTested).isEqualTo(999000L);
            assertThat(result.details).isEqualTo("{\"key\": \"value\"}");
        }
    }
}