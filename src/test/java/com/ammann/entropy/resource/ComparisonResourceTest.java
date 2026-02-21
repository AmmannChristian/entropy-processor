/* (C)2026 */
package com.ammann.entropy.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ammann.entropy.dto.EntropyComparisonResultDTO;
import com.ammann.entropy.dto.EntropyComparisonRunDTO;
import com.ammann.entropy.dto.EntropyComparisonSummaryDTO;
import com.ammann.entropy.enumeration.EntropySourceType;
import com.ammann.entropy.enumeration.JobStatus;
import com.ammann.entropy.model.EntropyComparisonResult;
import com.ammann.entropy.model.EntropyComparisonRun;
import com.ammann.entropy.properties.ApiProperties;
import com.ammann.entropy.service.EntropyComparisonService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ComparisonResourceTest {

    @Inject EntropyComparisonService comparisonService;

    // =========================================================================
    // Annotations / path
    // =========================================================================

    @Test
    void resource_classHasCorrectPath() {
        var path = ComparisonResource.class.getAnnotation(jakarta.ws.rs.Path.class);
        assertThat(path).isNotNull();
        assertThat(path.value())
                .isEqualTo(ApiProperties.BASE_URL_V1 + ApiProperties.Comparison.BASE);
    }

    @Test
    void resource_classRequiresAdminOrUserRole() {
        RolesAllowed roles = ComparisonResource.class.getAnnotation(RolesAllowed.class);
        assertThat(roles).isNotNull();
        assertThat(roles.value()).contains("ADMIN_ROLE", "USER_ROLE");
    }

    @Test
    void triggerEndpoint_requiresAdminRole() throws NoSuchMethodException {
        var method = ComparisonResource.class.getMethod("triggerComparison");
        RolesAllowed roles = method.getAnnotation(RolesAllowed.class);
        assertThat(roles).isNotNull();
        assertThat(roles.value()).containsExactly("ADMIN_ROLE");
    }

    // =========================================================================
    // getRecentRuns: limit capping behavior.
    // =========================================================================

    @Test
    @TestTransaction
    void getRecentRuns_defaultLimit10_returnsAtMost10() {
        EntropyComparisonRun.deleteAll();
        for (int i = 0; i < 12; i++) {
            persistRun(JobStatus.COMPLETED);
        }

        ComparisonResource resource = buildResource();
        Response response = resource.getRecentRuns(10);

        assertThat(response.getStatus()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<EntropyComparisonRunDTO> body = (List<EntropyComparisonRunDTO>) response.getEntity();
        assertThat(body).hasSizeLessThanOrEqualTo(10);
    }

    @Test
    @TestTransaction
    void getRecentRuns_limitAbove50_capsAt50() {
        EntropyComparisonRun.deleteAll();

        ComparisonResource resource = buildResource();
        // Verify that the limit is capped and no exception is thrown.
        Response response = resource.getRecentRuns(100);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @TestTransaction
    void getRecentRuns_limitBelow1_treatedAsOne() {
        EntropyComparisonRun.deleteAll();

        ComparisonResource resource = buildResource();
        Response response = resource.getRecentRuns(0);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @TestTransaction
    void getRecentRuns_emptyDatabase_returnsEmptyList() {
        EntropyComparisonRun.deleteAll();

        ComparisonResource resource = buildResource();
        Response response = resource.getRecentRuns(10);

        assertThat(response.getStatus()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<EntropyComparisonRunDTO> body = (List<EntropyComparisonRunDTO>) response.getEntity();
        assertThat(body).isEmpty();
    }

    @Test
    @TestTransaction
    void getRecentRuns_mapsRunsToDTO() {
        EntropyComparisonRun.deleteAll();
        persistRun(JobStatus.COMPLETED);

        ComparisonResource resource = buildResource();
        Response response = resource.getRecentRuns(10);

        @SuppressWarnings("unchecked")
        List<EntropyComparisonRunDTO> body = (List<EntropyComparisonRunDTO>) response.getEntity();
        assertThat(body).hasSize(1);
        assertThat(body.get(0).status()).isEqualTo("COMPLETED");
    }

    // =========================================================================
    // getRunResults
    // =========================================================================

    @Test
    @TestTransaction
    void getRunResults_returnsResultsForRun() {
        EntropyComparisonRun.deleteAll();
        EntropyComparisonResult.deleteAll();

        EntropyComparisonRun run = persistRun(JobStatus.COMPLETED);
        persistResult(run.id, EntropySourceType.BASELINE);
        persistResult(run.id, EntropySourceType.HARDWARE);

        ComparisonResource resource = buildResource();
        Response response = resource.getRunResults(run.id);

        assertThat(response.getStatus()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<EntropyComparisonResultDTO> body =
                (List<EntropyComparisonResultDTO>) response.getEntity();
        assertThat(body).hasSize(2);
    }

    @Test
    @TestTransaction
    void getRunResults_unknownRunId_returnsEmptyList() {
        EntropyComparisonRun.deleteAll();
        EntropyComparisonResult.deleteAll();

        ComparisonResource resource = buildResource();
        Response response = resource.getRunResults(Long.MAX_VALUE);

        assertThat(response.getStatus()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<EntropyComparisonResultDTO> body =
                (List<EntropyComparisonResultDTO>) response.getEntity();
        assertThat(body).isEmpty();
    }

    // =========================================================================
    // getSummary
    // =========================================================================

    @Test
    @TestTransaction
    void getSummary_returnsOkResponse() {
        EntropyComparisonRun.deleteAll();

        ComparisonResource resource = buildResource();
        Response response = resource.getSummary();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getEntity()).isInstanceOf(EntropyComparisonSummaryDTO.class);
    }

    @Test
    @TestTransaction
    void getSummary_withData_returnsNonNullLatestRun() {
        EntropyComparisonRun.deleteAll();
        EntropyComparisonResult.deleteAll();

        persistRun(JobStatus.COMPLETED);

        ComparisonResource resource = buildResource();
        Response response = resource.getSummary();

        EntropyComparisonSummaryDTO dto = (EntropyComparisonSummaryDTO) response.getEntity();
        assertThat(dto.latestRunId()).isNotNull();
        assertThat(dto.latestRunStatus()).isEqualTo("COMPLETED");
    }

    // =========================================================================
    // triggerComparison
    // =========================================================================

    @Test
    void triggerComparison_returns202Accepted() {
        ComparisonResource resource = buildResourceWithMockService();
        Response response = resource.triggerComparison();

        assertThat(response.getStatus()).isEqualTo(202);
    }

    @Test
    void triggerComparison_responseBodyContainsMessage() {
        ComparisonResource resource = buildResourceWithMockService();
        Response response = resource.triggerComparison();

        @SuppressWarnings("unchecked")
        java.util.Map<String, String> body = (java.util.Map<String, String>) response.getEntity();
        assertThat(body).containsKey("message");
    }

    // =========================================================================
    // ApiProperties.Comparison paths
    // =========================================================================

    @Test
    void comparison_apiProperties_pathsAreCorrect() {
        assertThat(ApiProperties.Comparison.BASE).isEqualTo("/comparison");
        assertThat(ApiProperties.Comparison.RESULTS).isEqualTo("/comparison/results");
        assertThat(ApiProperties.Comparison.SUMMARY).isEqualTo("/comparison/summary");
        assertThat(ApiProperties.Comparison.TRIGGER).isEqualTo("/comparison/trigger");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private ComparisonResource buildResource() {
        ComparisonResource resource = new ComparisonResource();
        resource.comparisonService = comparisonService;
        resource.executor = mock(ManagedExecutor.class);
        return resource;
    }

    private ComparisonResource buildResourceWithMockService() {
        EntropyComparisonService mockService = mock(EntropyComparisonService.class);
        when(mockService.getRecentRuns(10)).thenReturn(List.of());
        when(mockService.getSummary())
                .thenReturn(new EntropyComparisonSummaryDTO(null, null, null, List.of(), 0L));

        ComparisonResource resource = new ComparisonResource();
        resource.comparisonService = mockService;
        resource.executor = mock(ManagedExecutor.class);
        return resource;
    }

    private EntropyComparisonRun persistRun(JobStatus status) {
        EntropyComparisonRun run = new EntropyComparisonRun();
        run.runTimestamp = Instant.now();
        run.status = status;
        run.sp80022SampleSizeBytes = 4_194_304;
        run.sp80090bSampleSizeBytes = 4_194_304;
        run.metricsSampleSizeBytes = 1_048_576;
        run.createdAt = Instant.now();
        if (status == JobStatus.COMPLETED) {
            run.completedAt = Instant.now();
            run.mixedValid = false;
        }
        run.persist();
        return run;
    }

    private void persistResult(Long runId, EntropySourceType sourceType) {
        EntropyComparisonResult result = new EntropyComparisonResult();
        result.comparisonRunId = runId;
        result.sourceType = sourceType;
        result.bytesCollected = 0;
        result.nist22Status = "INSUFFICIENT_DATA";
        result.nist90bStatus = "INSUFFICIENT_DATA";
        result.createdAt = Instant.now();
        result.persist();
    }
}
