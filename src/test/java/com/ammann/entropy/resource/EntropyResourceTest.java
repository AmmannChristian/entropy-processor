/* (C)2026 */
package com.ammann.entropy.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ammann.entropy.dto.EntropyStatisticsDTO;
import com.ammann.entropy.dto.ErrorResponseDTO;
import com.ammann.entropy.dto.NIST90BResultDTO;
import com.ammann.entropy.dto.NISTSuiteResultDTO;
import com.ammann.entropy.dto.NistValidationJobDTO;
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
import jakarta.ws.rs.core.SecurityContext;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
                    public EntropyAnalysisResult calculateAllEntropies(
                            List<Long> intervalsNs, int bucketSizeNs) {
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
        when(resource.nistValidationService.startAsyncSp80022Validation(any(), any(), any(), any()))
                .thenThrow(new NistException("No entropy data"));

        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getUserPrincipal()).thenReturn(null);

        var response =
                resource.triggerNISTValidation(
                        "2024-01-01T00:00:00Z",
                        "2024-01-01T00:01:00Z",
                        "Bearer token",
                        securityContext);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getEntity()).isInstanceOf(ErrorResponseDTO.class);
    }

    // parseTimeWindow: invalid timestamps and inverted window.

    @Test
    void parseTimeWindow_invalidToTimestamp_throwsValidationException() {
        EntropyResource resource = buildResource();

        assertThatThrownBy(() -> resource.getShannonEntropy(null, "not-a-timestamp", 1_000))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void parseTimeWindow_invalidFromTimestamp_throwsValidationException() {
        EntropyResource resource = buildResource();

        assertThatThrownBy(
                        () -> resource.getShannonEntropy("bad-from", "2024-01-01T01:00:00Z", 1_000))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void parseTimeWindow_startAfterEnd_throwsValidationException() {
        EntropyResource resource = buildResource();

        // from greater than to triggers start.isAfter(end) and raises ValidationException.
        assertThatThrownBy(
                        () ->
                                resource.getShannonEntropy(
                                        "2024-01-02T00:00:00Z", "2024-01-01T00:00:00Z", 1_000))
                .isInstanceOf(ValidationException.class);
    }

    // triggerNISTValidation: ValidationException maps to 400 and non-null principal yields
    // username.

    @Test
    void triggerNistValidation_validationException_returns400() {
        EntropyResource resource = buildResource();
        when(resource.nistValidationService.startAsyncSp80022Validation(any(), any(), any(), any()))
                .thenThrow(new ValidationException("Too many jobs"));

        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getUserPrincipal()).thenReturn(null);

        var response =
                resource.triggerNISTValidation(
                        "2024-01-01T00:00:00Z", "2024-01-01T01:00:00Z", null, securityContext);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getEntity()).isInstanceOf(ErrorResponseDTO.class);
    }

    @Test
    void triggerNistValidation_withPrincipal_usesUsername() {
        EntropyResource resource = buildResource();
        UUID jobId = UUID.randomUUID();
        when(resource.nistValidationService.startAsyncSp80022Validation(any(), any(), any(), any()))
                .thenReturn(jobId);

        Principal principal = () -> "admin-user";
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getUserPrincipal()).thenReturn(principal);

        var response =
                resource.triggerNISTValidation(
                        "2024-01-01T00:00:00Z",
                        "2024-01-01T01:00:00Z",
                        "Bearer some-token",
                        securityContext);

        assertThat(response.getStatus()).isEqualTo(202);
    }

    // triggerNIST90BValidation

    @Test
    void triggerNist90BValidation_success_returns202() {
        EntropyResource resource = buildResource();
        UUID jobId = UUID.randomUUID();
        when(resource.nistValidationService.startAsyncSp80090bValidation(
                        any(), any(), any(), any()))
                .thenReturn(jobId);

        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getUserPrincipal()).thenReturn(null);

        var response =
                resource.triggerNIST90BValidation(
                        "2024-01-01T00:00:00Z", "2024-01-01T01:00:00Z", null, securityContext);

        assertThat(response.getStatus()).isEqualTo(202);
    }

    @Test
    void triggerNist90BValidation_validationException_returns400() {
        EntropyResource resource = buildResource();
        when(resource.nistValidationService.startAsyncSp80090bValidation(
                        any(), any(), any(), any()))
                .thenThrow(new ValidationException("Too many jobs"));

        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getUserPrincipal()).thenReturn(null);

        var response =
                resource.triggerNIST90BValidation(
                        "2024-01-01T00:00:00Z", "2024-01-01T01:00:00Z", null, securityContext);

        assertThat(response.getStatus()).isEqualTo(400);
    }

    // getValidationJobStatus

    @Test
    void getValidationJobStatus_found_returns200() {
        EntropyResource resource = buildResource();
        UUID jobId = UUID.randomUUID();
        NistValidationJobDTO dto =
                new NistValidationJobDTO(
                        jobId,
                        "SP_800_22",
                        "RUNNING",
                        50,
                        1,
                        2,
                        Instant.now(),
                        null,
                        null,
                        null,
                        null,
                        null);
        when(resource.nistValidationService.getJobStatus(jobId)).thenReturn(dto);

        var response = resource.getValidationJobStatus(jobId);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getEntity()).isEqualTo(dto);
    }

    @Test
    void getValidationJobStatus_notFound_returns404() {
        EntropyResource resource = buildResource();
        UUID jobId = UUID.randomUUID();
        when(resource.nistValidationService.getJobStatus(jobId))
                .thenThrow(new ValidationException("Job not found"));

        var response = resource.getValidationJobStatus(jobId);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getEntity()).isInstanceOf(ErrorResponseDTO.class);
    }

    // getValidationJobResult (SP 800-22)

    @Test
    void getValidationJobResult_found_returns200() {
        EntropyResource resource = buildResource();
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.now();
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
        when(resource.nistValidationService.getSp80022JobResult(jobId)).thenReturn(dto);

        var response = resource.getValidationJobResult(jobId);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getEntity()).isEqualTo(dto);
    }

    @Test
    void getValidationJobResult_notCompleted_returns400() {
        EntropyResource resource = buildResource();
        UUID jobId = UUID.randomUUID();
        when(resource.nistValidationService.getSp80022JobResult(jobId))
                .thenThrow(new ValidationException("Job not completed yet"));

        var response = resource.getValidationJobResult(jobId);

        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void getValidationJobResult_notFound_returns404() {
        EntropyResource resource = buildResource();
        UUID jobId = UUID.randomUUID();
        when(resource.nistValidationService.getSp80022JobResult(jobId))
                .thenThrow(new ValidationException("Job not found"));

        var response = resource.getValidationJobResult(jobId);

        assertThat(response.getStatus()).isEqualTo(404);
    }

    // get90BValidationJobResult (SP 800-90B)

    @Test
    void get90bValidationJobResult_found_returns200() {
        EntropyResource resource = buildResource();
        UUID jobId = UUID.randomUUID();
        NIST90BResultDTO dto = new NIST90BResultDTO(7.5, true, "OK", Instant.now(), 0L, null, null);
        when(resource.nistValidationService.getSp80090bJobResult(jobId)).thenReturn(dto);

        var response = resource.get90BValidationJobResult(jobId);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getEntity()).isEqualTo(dto);
    }

    @Test
    void get90bValidationJobResult_notCompleted_returns400() {
        EntropyResource resource = buildResource();
        UUID jobId = UUID.randomUUID();
        when(resource.nistValidationService.getSp80090bJobResult(jobId))
                .thenThrow(new ValidationException("Job not completed yet"));

        var response = resource.get90BValidationJobResult(jobId);

        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void get90bValidationJobResult_notFound_returns404() {
        EntropyResource resource = buildResource();
        UUID jobId = UUID.randomUUID();
        when(resource.nistValidationService.getSp80090bJobResult(jobId))
                .thenThrow(new ValidationException("Job not found"));

        var response = resource.get90BValidationJobResult(jobId);

        assertThat(response.getStatus()).isEqualTo(404);
    }

    // getWindowAnalysis: valid timestamps, not only missing-parameter rejection.

    @Test
    @TestTransaction
    void getWindowAnalysis_withData_returnsDto() {
        EntropyData.deleteAll();
        EntropyResource resource = buildResource();

        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = start.plusSeconds(10);

        // At least 4 events are required to produce 3 intervals for sample entropy with m=2.
        EntropyData.persist(
                TestDataFactory.createEntropyEvent(1, 1_000_000L, start.plusSeconds(1)),
                TestDataFactory.createEntropyEvent(2, 2_000_000L, start.plusSeconds(2)),
                TestDataFactory.createEntropyEvent(3, 3_000_000L, start.plusSeconds(3)),
                TestDataFactory.createEntropyEvent(4, 4_000_000L, start.plusSeconds(4)));
        Panache.getEntityManager().flush();

        var response = resource.getWindowAnalysis(start.toString(), end.toString(), 1_000_000);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    // getShannonEntropy: null from and to values use the default window path.

    @Test
    @TestTransaction
    void getShannonEntropy_nullFromAndTo_usesDefaultWindow() {
        EntropyData.deleteAll();
        EntropyResource resource = buildResource();

        Instant now = Instant.now();
        EntropyData.persist(
                TestDataFactory.createEntropyEvent(1, 1_000_000L, now.minusSeconds(30)),
                TestDataFactory.createEntropyEvent(2, 2_000_000L, now.minusSeconds(20)));
        Panache.getEntityManager().flush();

        // Null from and to values default to the last one-hour window.
        var response = resource.getShannonEntropy(null, null, 1_000_000);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private EntropyResource buildResource() {
        EntropyResource resource = new EntropyResource();
        resource.entropyStatisticsService = new EntropyStatisticsService();
        resource.nistValidationService = mock(NistValidationService.class);
        return resource;
    }
}
