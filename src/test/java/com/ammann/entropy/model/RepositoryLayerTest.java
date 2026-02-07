/* (C)2026 */
package com.ammann.entropy.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.ammann.entropy.dto.DataQualityReportDTO;
import com.ammann.entropy.dto.NISTTestResultDTO;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RepositoryLayerTest {

    @Test
    void dataQualityReportCalculatesScoreAndDto() {
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = start.plusSeconds(60);

        DataQualityReport report = new DataQualityReport(start, end, 100L);
        report.missingSequenceCount = 10;
        report.clockDriftUsPerHour = 55.0;
        report.decayRateRealistic = false;
        report.averageNetworkDelayMs = 150.0;
        report.calculateQualityScore();

        assertThat(report.overallQualityScore).isBetween(0.0, 1.0);

        DataQualityReportDTO dto = report.toDTO();
        assertThat(dto.totalEvents()).isEqualTo(100L);
        assertThat(dto.missingSequenceCount()).isEqualTo(10L);
        assertThat(dto.qualityScore()).isEqualTo(report.overallQualityScore);
    }

    @Test
    void nistTestResultToDtoSetsStatus() {
        Instant now = Instant.parse("2024-01-01T00:00:00Z");
        NistTestResult pass =
                new NistTestResult(UUID.randomUUID(), "Frequency", true, 0.5, now, now);
        NistTestResult fail = new NistTestResult(UUID.randomUUID(), "Runs", false, 0.01, now, now);

        NISTTestResultDTO passDto = pass.toDTO();
        NISTTestResultDTO failDto = fail.toDTO();

        assertThat(passDto.status()).isEqualTo("PASS");
        assertThat(failDto.status()).isEqualTo("FAIL");
    }
}
