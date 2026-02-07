package com.ammann.entropy.model;

import com.ammann.entropy.dto.DataQualityReportDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DataQualityReportTest {

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        void defaultConstructorInitializesReportTimestamp() {
            DataQualityReport report = new DataQualityReport();
            assertThat(report.reportTimestamp).isNotNull();
        }

        @Test
        void parameterizedConstructorSetsFields() {
            Instant start = Instant.parse("2024-01-01T00:00:00Z");
            Instant end = Instant.parse("2024-01-01T01:00:00Z");

            DataQualityReport report = new DataQualityReport(start, end, 1000L);

            assertThat(report.windowStart).isEqualTo(start);
            assertThat(report.windowEnd).isEqualTo(end);
            assertThat(report.totalEvents).isEqualTo(1000L);
        }
    }

    @Nested
    @DisplayName("calculateQualityScore Tests")
    class QualityScoreTests {

        @Test
        void perfectScoreWhenNoIssues() {
            DataQualityReport report = createReport();
            report.missingSequenceCount = 0;
            report.clockDriftUsPerHour = 5.0;
            report.decayRateRealistic = true;
            report.averageNetworkDelayMs = 50.0;

            report.calculateQualityScore();

            assertThat(report.overallQualityScore).isEqualTo(1.0);
        }

        @Test
        void penalizesPacketLoss() {
            DataQualityReport report = createReport();
            report.totalEvents = 1000L;
            report.missingSequenceCount = 100; // 10% loss

            report.calculateQualityScore();

            assertThat(report.overallQualityScore).isEqualTo(0.9);
        }

        @Test
        void penalizesSignificantClockDrift() {
            DataQualityReport report = createReport();
            report.clockDriftUsPerHour = 15.0; // > 10 threshold

            report.calculateQualityScore();

            assertThat(report.overallQualityScore).isEqualTo(0.95);
        }

        @Test
        void penalizesSevereClockDrift() {
            DataQualityReport report = createReport();
            report.clockDriftUsPerHour = 60.0; // > 50 threshold

            report.calculateQualityScore();

            // 0.95 * 0.85 = 0.8075
            assertThat(report.overallQualityScore).isCloseTo(0.8075, org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        void penalizesUnrealisticDecayRate() {
            DataQualityReport report = createReport();
            report.decayRateRealistic = false;

            report.calculateQualityScore();

            assertThat(report.overallQualityScore).isEqualTo(0.9);
        }

        @Test
        void penalizesHighNetworkDelay() {
            DataQualityReport report = createReport();
            report.averageNetworkDelayMs = 150.0; // > 100 threshold

            report.calculateQualityScore();

            assertThat(report.overallQualityScore).isEqualTo(0.95);
        }

        @Test
        void cumulativePenalties() {
            DataQualityReport report = createReport();
            report.totalEvents = 1000L;
            report.missingSequenceCount = 100; // 10% loss -> 0.9
            report.clockDriftUsPerHour = 15.0; // -> *0.95
            report.decayRateRealistic = false; // -> *0.9

            report.calculateQualityScore();

            // 0.9 * 0.95 * 0.9 = 0.7695
            assertThat(report.overallQualityScore).isCloseTo(0.7695, org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        void clampsScoreToValidRange() {
            DataQualityReport report = createReport();
            report.totalEvents = 100L;
            report.missingSequenceCount = 200; // > 100% would be negative

            report.calculateQualityScore();

            assertThat(report.overallQualityScore).isGreaterThanOrEqualTo(0.0);
            assertThat(report.overallQualityScore).isLessThanOrEqualTo(1.0);
        }

        private DataQualityReport createReport() {
            DataQualityReport report = new DataQualityReport();
            report.totalEvents = 1000L;
            report.missingSequenceCount = 0;
            return report;
        }
    }

    @Nested
    @DisplayName("toDTO Tests")
    class ToDtoTests {

        @Test
        void convertsToDtoWithAllFields() {
            Instant start = Instant.parse("2024-01-01T00:00:00Z");
            Instant end = Instant.parse("2024-01-01T01:00:00Z");
            Instant reportTime = Instant.parse("2024-01-01T01:05:00Z");

            DataQualityReport report = new DataQualityReport(start, end, 5000L);
            report.reportTimestamp = reportTime;
            report.missingSequenceCount = 10;
            report.clockDriftUsPerHour = 5.5;
            report.averageDecayIntervalMs = 2.5;
            report.averageNetworkDelayMs = 25.0;
            report.overallQualityScore = 0.95;

            DataQualityReportDTO dto = report.toDTO();

            assertThat(dto.totalEvents()).isEqualTo(5000L);
            assertThat(dto.missingSequenceCount()).isEqualTo(10);
            assertThat(dto.clockDrift()).isNotNull();
            assertThat(dto.clockDrift().driftRateUsPerHour()).isEqualTo(5.5);
            assertThat(dto.decayRate()).isNotNull();
            assertThat(dto.decayRate().averageIntervalMs()).isEqualTo(2.5);
            assertThat(dto.averageNetworkDelayMs()).isEqualTo(25.0);
            assertThat(dto.qualityScore()).isEqualTo(0.95);
            assertThat(dto.assessmentTimestamp()).isEqualTo(reportTime);
        }

        @Test
        void handlesNullClockDrift() {
            DataQualityReport report = new DataQualityReport();
            report.totalEvents = 100L;
            report.clockDriftUsPerHour = null;

            DataQualityReportDTO dto = report.toDTO();

            assertThat(dto.clockDrift()).isNull();
        }

        @Test
        void handlesNullDecayRate() {
            DataQualityReport report = new DataQualityReport();
            report.totalEvents = 100L;
            report.averageDecayIntervalMs = null;

            DataQualityReportDTO dto = report.toDTO();

            assertThat(dto.decayRate()).isNull();
        }
    }

    @Nested
    @DisplayName("toString Tests")
    class ToStringTests {

        @Test
        void formatsCorrectly() {
            Instant start = Instant.parse("2024-01-01T00:00:00Z");
            Instant end = Instant.parse("2024-01-01T01:00:00Z");

            DataQualityReport report = new DataQualityReport(start, end, 1000L);
            report.overallQualityScore = 0.95;
            report.missingSequenceCount = 5;

            String result = report.toString();

            assertThat(result).contains("DataQualityReport");
            assertThat(result).contains("events=1000");
            assertThat(result).contains("score=0.950");
            assertThat(result).contains("missing=5");
        }
    }
}