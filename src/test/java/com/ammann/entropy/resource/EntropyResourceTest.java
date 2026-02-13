/* (C)2026 */
package com.ammann.entropy.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ammann.entropy.dto.EntropyStatisticsDTO;
import com.ammann.entropy.dto.ErrorResponseDTO;
import com.ammann.entropy.dto.NISTSuiteResultDTO;
import com.ammann.entropy.dto.RenyiEntropyResponseDTO;
import com.ammann.entropy.dto.ShannonEntropyResponseDTO;
import com.ammann.entropy.dto.TimeWindowDTO;
import com.ammann.entropy.exception.NistException;
import com.ammann.entropy.exception.ValidationException;
import com.ammann.entropy.model.EntropyData;
import com.ammann.entropy.service.EntropyStatisticsService;
import com.ammann.entropy.service.NistValidationService;
import com.ammann.entropy.support.TestDataFactory;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class EntropyResourceTest {

    @Test
    @TestTransaction
    void getShannonEntropyReturnsDto() {
        EntropyData.deleteAll();
        EntropyResource resource = buildResource();

        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = start.plusSeconds(10);

        EntropyData.persist(
                TestDataFactory.createEntropyEvent(1, 1_000_000L, start.plusSeconds(1)),
                TestDataFactory.createEntropyEvent(2, 2_000_000L, start.plusSeconds(2)),
                TestDataFactory.createEntropyEvent(3, 3_000_000L, start.plusSeconds(3)),
                TestDataFactory.createEntropyEvent(4, 4_000_000L, start.plusSeconds(4)),
                TestDataFactory.createEntropyEvent(5, 5_000_000L, start.plusSeconds(5)),
                TestDataFactory.createEntropyEvent(6, 6_000_000L, start.plusSeconds(6)));
        Panache.getEntityManager().flush();

        var response = resource.getShannonEntropy(start.toString(), end.toString(), 1_000_000);
        ShannonEntropyResponseDTO dto = (ShannonEntropyResponseDTO) response.getEntity();

        assertThat(dto.sampleCount()).isEqualTo(5L);
        assertThat(dto.shannonEntropy()).isNotNaN();
    }

    @Test
    void getRenyiEntropyRejectsInvalidAlpha() {
        EntropyResource resource = buildResource();

        assertThatThrownBy(() -> resource.getRenyiEntropy(null, null, 0.0, 1_000_000))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @TestTransaction
    void getRenyiEntropyReturnsDto() {
        EntropyData.deleteAll();
        EntropyResource resource = buildResource();

        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = start.plusSeconds(3);

        EntropyData.persist(
                TestDataFactory.createEntropyEvent(1, 1_000_000L, start.plusMillis(10)),
                TestDataFactory.createEntropyEvent(2, 2_000_000L, start.plusMillis(20)));

        var response = resource.getRenyiEntropy(start.toString(), end.toString(), 2.0, 1_000_000);
        RenyiEntropyResponseDTO dto = (RenyiEntropyResponseDTO) response.getEntity();

        assertThat(dto.sampleCount()).isEqualTo(1L);
        assertThat(dto.renyiEntropy()).isNotNaN();
    }

    @Test
    @TestTransaction
    void getComprehensiveEntropyReturnsDto() {
        EntropyData.deleteAll();
        EntropyResource resource = new EntropyResource();
        resource.entropyStatisticsService =
                new EntropyStatisticsService() {
                    @Override
                    public EntropyAnalysisResult calculateAllEntropies(List<Long> intervalsNs, int bucketSizeNs) {
                        var stats =
                                new BasicStatistics(
                                        intervalsNs.size(),
                                        intervalsNs.stream().mapToLong(Long::longValue).sum(),
                                        intervalsNs.stream()
                                                .mapToLong(Long::longValue)
                                                .min()
                                                .orElse(0L),
                                        intervalsNs.stream()
                                                .mapToLong(Long::longValue)
                                                .max()
                                                .orElse(0L),
                                        intervalsNs.stream()
                                                .mapToLong(Long::longValue)
                                                .average()
                                                .orElse(0.0),
                                        0.0,
                                        0.0);
                        return new EntropyAnalysisResult(
                                intervalsNs.size(), 1.23, 1.01, 0.42, 0.24, stats, 10L);
                    }
                };
        resource.nistValidationService = mock(NistValidationService.class);

        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = start.plusSeconds(3);

        EntropyData.persist(
                TestDataFactory.createEntropyEvent(1, 1_000_000L, start.plusMillis(10)),
                TestDataFactory.createEntropyEvent(2, 2_000_000L, start.plusMillis(20)));

        var response = resource.getComprehensiveEntropy(start.toString(), end.toString(), 1_000);
        EntropyStatisticsDTO dto = (EntropyStatisticsDTO) response.getEntity();

        assertThat(dto.sampleCount()).isEqualTo(1L);
        assertThat(dto.shannonEntropy()).isNotNaN();
    }

    @Test
    void getWindowAnalysisRejectsMissingFromTo() {
        EntropyResource resource = buildResource();

        assertThatThrownBy(() -> resource.getWindowAnalysis(null, null, 1_000))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void getLatestNistResultsReturnsNullWhenMissing() {
        EntropyResource resource = buildResource();
        when(resource.nistValidationService.getLatestValidationResult()).thenReturn(null);

        var response = resource.getLatestNISTResults();
        assertThat(response.getEntity()).isNull();
    }

    @Test
    void getLatestNistResultsReturnsDto() {
        EntropyResource resource = buildResource();
        Instant now = Instant.parse("2024-01-01T00:00:00Z");
        NISTSuiteResultDTO dto =
                new NISTSuiteResultDTO(
                        List.of(),
                        1,
                        1,
                        0,
                        1.0,
                        true,
                        now,
                        1024L,
                        new TimeWindowDTO(now.minusSeconds(60), now, 1L));
        when(resource.nistValidationService.getLatestValidationResult()).thenReturn(dto);

        var response = resource.getLatestNISTResults();
        assertThat(response.getEntity()).isEqualTo(dto);
    }

    @Test
    void triggerNistValidationReturnsServiceUnavailableWhenNoData() {
        EntropyResource resource = buildResource();
        when(resource.nistValidationService.validateTimeWindow(any(), any(), any()))
                .thenThrow(new NistException("No entropy data"));

        var response =
                resource.triggerNISTValidation(
                        "2024-01-01T00:00:00Z", "2024-01-01T00:01:00Z", "Bearer token");

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getEntity()).isInstanceOf(ErrorResponseDTO.class);
    }

    private EntropyResource buildResource() {
        EntropyResource resource = new EntropyResource();
        resource.entropyStatisticsService = new EntropyStatisticsService();
        resource.nistValidationService = mock(NistValidationService.class);
        return resource;
    }
}
