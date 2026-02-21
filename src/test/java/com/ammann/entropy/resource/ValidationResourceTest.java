/* (C)2026 */
package com.ammann.entropy.resource;

import static org.assertj.core.api.Assertions.assertThat;

import com.ammann.entropy.dto.NIST90BEstimatorResultDTO;
import com.ammann.entropy.enumeration.TestType;
import com.ammann.entropy.model.Nist90BEstimatorResult;
import com.ammann.entropy.model.Nist90BResult;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for ValidationResource REST endpoints.
 *
 * <p>Focuses on V2a migration endpoints for NIST SP 800-90B estimator results.
 * Uses direct method invocation (not HTTP) to avoid transaction isolation issues
 * with {@code @TestTransaction}.
 */
@QuarkusTest
class ValidationResourceTest {

    /** Direct instantiation bypasses CDI security proxy (@RolesAllowed interceptor). */
    private final ValidationResource resource = new ValidationResource();

    @AfterEach
    @TestTransaction
    void cleanup() {
        Nist90BEstimatorResult.deleteAll();
        Nist90BResult.deleteAll();
    }

    @Test
    @TestTransaction
    void getEstimators_nonExistentRunId_returns404() {
        UUID nonExistent = UUID.randomUUID();

        Response response = resource.getEstimatorResults(nonExistent, null);

        assertThat(response.getStatus()).isEqualTo(404);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertThat(body.get("error")).isEqualTo("Assessment not found");
    }

    @Test
    @TestTransaction
    void getEstimators_noEstimatorRows_returnsEmptyArray() {
        UUID assessmentRunId = UUID.randomUUID();
        Nist90BResult result = createAssessmentWithoutEstimators(assessmentRunId);
        result.persist();

        Response response = resource.getEstimatorResults(assessmentRunId, null);

        assertThat(response.getStatus()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<NIST90BEstimatorResultDTO> dtos =
                (List<NIST90BEstimatorResultDTO>) response.getEntity();
        assertThat(dtos).isEmpty();
    }

    @Test
    @TestTransaction
    void getEstimators_invalidTestType_returns400() {
        UUID assessmentRunId = UUID.randomUUID();
        Nist90BResult result = createAssessmentWithoutEstimators(assessmentRunId);
        result.persist();

        Response response = resource.getEstimatorResults(assessmentRunId, "INVALID");

        assertThat(response.getStatus()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertThat(body.get("error").toString())
                .contains("Invalid testType. Must be IID or NON_IID");
    }

    @Test
    @TestTransaction
    void getEstimators_caseInsensitive_works() {
        UUID assessmentRunId = setupTestDataWithEstimators();

        // Test lowercase "iid"
        Response r1 = resource.getEstimatorResults(assessmentRunId, "iid");
        assertThat(r1.getStatus()).isEqualTo(200);
        assertThat(asDtos(r1)).hasSize(2);

        // Test uppercase "IID"
        Response r2 = resource.getEstimatorResults(assessmentRunId, "IID");
        assertThat(r2.getStatus()).isEqualTo(200);
        assertThat(asDtos(r2)).hasSize(2);

        // Test mixed case "non_iid"
        Response r3 = resource.getEstimatorResults(assessmentRunId, "non_iid");
        assertThat(r3.getStatus()).isEqualTo(200);
        assertThat(asDtos(r3)).hasSize(3);

        // Test uppercase "NON_IID"
        Response r4 = resource.getEstimatorResults(assessmentRunId, "NON_IID");
        assertThat(r4.getStatus()).isEqualTo(200);
        assertThat(asDtos(r4)).hasSize(3);
    }

    @Test
    @TestTransaction
    void getEstimators_noTestTypeParam_returnsAll() {
        UUID assessmentRunId = setupTestDataWithEstimators();

        Response response = resource.getEstimatorResults(assessmentRunId, null);

        assertThat(response.getStatus()).isEqualTo(200);
        List<NIST90BEstimatorResultDTO> dtos = asDtos(response);
        assertThat(dtos).hasSize(5); // 3 Non-IID + 2 IID
        assertThat(dtos.get(0).testType()).isNotNull();
        assertThat(dtos.get(0).estimatorName()).isNotNull();
        assertThat(dtos.get(0).passed()).isNotNull();
    }

    @Test
    @TestTransaction
    void getEstimators_filterByIID_returnsOnlyIID() {
        UUID assessmentRunId = setupTestDataWithEstimators();

        Response response = resource.getEstimatorResults(assessmentRunId, "IID");

        assertThat(response.getStatus()).isEqualTo(200);
        List<NIST90BEstimatorResultDTO> dtos = asDtos(response);
        assertThat(dtos).hasSize(2);
        assertThat(dtos.get(0).testType()).isEqualTo("IID");
        assertThat(dtos.get(1).testType()).isEqualTo("IID");
        assertThat(dtos.get(0).estimatorName()).isEqualTo("Chi-Square Test");
        assertThat(dtos.get(1).estimatorName()).isEqualTo("LRS Test");
    }

    @Test
    @TestTransaction
    void getEstimators_filterByNonIID_returnsOnlyNonIID() {
        UUID assessmentRunId = setupTestDataWithEstimators();

        Response response = resource.getEstimatorResults(assessmentRunId, "NON_IID");

        assertThat(response.getStatus()).isEqualTo(200);
        List<NIST90BEstimatorResultDTO> dtos = asDtos(response);
        assertThat(dtos).hasSize(3);
        assertThat(dtos.get(0).testType()).isEqualTo("NON_IID");
        assertThat(dtos.get(1).testType()).isEqualTo("NON_IID");
        assertThat(dtos.get(2).testType()).isEqualTo("NON_IID");
    }

    @Test
    @TestTransaction
    void getEstimators_returnsNullForNonEntropyTests() {
        UUID assessmentRunId = UUID.randomUUID();
        Nist90BResult result = createAssessmentWithoutEstimators(assessmentRunId);
        result.persist();

        // Add estimator with NULL entropy (non-entropy test)
        Nist90BEstimatorResult estimator =
                new Nist90BEstimatorResult(
                        assessmentRunId,
                        TestType.IID,
                        "Chi-Square Test",
                        null, // NULL entropy
                        true,
                        null,
                        "Tests independence");
        estimator.persist();

        Response response = resource.getEstimatorResults(assessmentRunId, null);

        assertThat(response.getStatus()).isEqualTo(200);
        List<NIST90BEstimatorResultDTO> dtos = asDtos(response);
        assertThat(dtos).hasSize(1);
        assertThat(dtos.get(0).entropyEstimate()).isNull(); // NULL, not 0.0
        assertThat(dtos.get(0).passed()).isTrue();
    }

    @Test
    @TestTransaction
    void getEstimators_returnsZeroForDegenerateSource() {
        UUID assessmentRunId = UUID.randomUUID();
        Nist90BResult result = createAssessmentWithoutEstimators(assessmentRunId);
        result.persist();

        // Add estimator with 0.0 entropy (degenerate source)
        Nist90BEstimatorResult estimator =
                new Nist90BEstimatorResult(
                        assessmentRunId,
                        TestType.NON_IID,
                        "Collision Estimate",
                        0.0, // True zero
                        false,
                        null,
                        "Degenerate source");
        estimator.persist();

        Response response = resource.getEstimatorResults(assessmentRunId, null);

        assertThat(response.getStatus()).isEqualTo(200);
        List<NIST90BEstimatorResultDTO> dtos = asDtos(response);
        assertThat(dtos).hasSize(1);
        assertThat(dtos.get(0).entropyEstimate()).isEqualTo(0.0); // 0.0, not null
        assertThat(dtos.get(0).passed()).isFalse();
    }

    @Test
    @TestTransaction
    void getEstimators_returnsDetailsJson() {
        UUID assessmentRunId = UUID.randomUUID();
        Nist90BResult result = createAssessmentWithoutEstimators(assessmentRunId);
        result.persist();

        // Add estimator with details
        Map<String, Double> details = new HashMap<>();
        details.put("chi_square", 12.45);
        details.put("df", 10.0);

        Nist90BEstimatorResult estimator =
                new Nist90BEstimatorResult(
                        assessmentRunId,
                        TestType.IID,
                        "Chi-Square Test",
                        null,
                        true,
                        details,
                        "Chi-square test");
        estimator.persist();

        Response response = resource.getEstimatorResults(assessmentRunId, null);

        assertThat(response.getStatus()).isEqualTo(200);
        List<NIST90BEstimatorResultDTO> dtos = asDtos(response);
        assertThat(dtos).hasSize(1);
        assertThat(dtos.get(0).details()).containsEntry("chi_square", 12.45);
        assertThat(dtos.get(0).details()).containsEntry("df", 10.0);
    }

    // ==================== Test Helper Methods ====================

    @SuppressWarnings("unchecked")
    private List<NIST90BEstimatorResultDTO> asDtos(Response response) {
        return (List<NIST90BEstimatorResultDTO>) response.getEntity();
    }

    private Nist90BResult createAssessmentWithoutEstimators(UUID assessmentRunId) {
        Instant now = Instant.now();
        Nist90BResult result =
                new Nist90BResult(
                        "test-batch",
                        7.5, // minEntropy
                        true, // passed
                        "{\"summary\":\"ok\"}", // details
                        1000000L, // bits
                        now.minusSeconds(60), // start
                        now // end
                        );
        result.assessmentRunId = assessmentRunId;
        return result;
    }

    private UUID setupTestDataWithEstimators() {
        UUID assessmentRunId = UUID.randomUUID();
        Nist90BResult result = createAssessmentWithoutEstimators(assessmentRunId);
        result.persist();

        // Add 3 Non-IID estimators
        new Nist90BEstimatorResult(
                        assessmentRunId,
                        TestType.NON_IID,
                        "Collision Estimate",
                        7.1,
                        true,
                        null,
                        "Collision test")
                .persist();

        new Nist90BEstimatorResult(
                        assessmentRunId,
                        TestType.NON_IID,
                        "Compression Estimate",
                        7.0,
                        true,
                        null,
                        "Compression test")
                .persist();

        new Nist90BEstimatorResult(
                        assessmentRunId,
                        TestType.NON_IID,
                        "Markov Estimate",
                        6.9,
                        true,
                        null,
                        "Markov test")
                .persist();

        // Add 2 IID tests (non-entropy, null estimates)
        new Nist90BEstimatorResult(
                        assessmentRunId,
                        TestType.IID,
                        "Chi-Square Test",
                        null,
                        true,
                        null,
                        "Chi-square test")
                .persist();

        new Nist90BEstimatorResult(
                        assessmentRunId, TestType.IID, "LRS Test", null, true, null, "LRS test")
                .persist();

        return assessmentRunId;
    }
}
