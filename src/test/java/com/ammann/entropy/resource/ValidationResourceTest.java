/* (C)2026 */
package com.ammann.entropy.resource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import com.ammann.entropy.enumeration.TestType;
import com.ammann.entropy.model.Nist90BEstimatorResult;
import com.ammann.entropy.model.Nist90BResult;
import com.ammann.entropy.properties.ApiProperties;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for ValidationResource REST endpoints.
 *
 * <p>Focuses on V2a migration endpoints for NIST SP 800-90B estimator results.
 * Note: Security is handled by application.properties test profile.
 */
@QuarkusTest
class ValidationResourceTest {

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

        given().when()
                .get(
                        ApiProperties.BASE_URL_V1
                                + "/validation/90b-results/"
                                + nonExistent
                                + "/estimators")
                .then()
                .statusCode(404)
                .body("error", equalTo("Assessment not found"));
    }

    @Test
    @TestTransaction
    void getEstimators_noEstimatorRows_returnsEmptyArray() {
        // Create assessment without estimators
        UUID assessmentRunId = UUID.randomUUID();
        Nist90BResult result = createAssessmentWithoutEstimators(assessmentRunId);
        result.persist();

        given().when()
                .get(
                        ApiProperties.BASE_URL_V1
                                + "/validation/90b-results/"
                                + assessmentRunId
                                + "/estimators")
                .then()
                .statusCode(200)
                .body("$", hasSize(0)); // Empty array
    }

    @Test
    @TestTransaction
    void getEstimators_invalidTestType_returns400() {
        UUID assessmentRunId = UUID.randomUUID();
        Nist90BResult result = createAssessmentWithoutEstimators(assessmentRunId);
        result.persist();

        given().when()
                .get(
                        ApiProperties.BASE_URL_V1
                                + "/validation/90b-results/"
                                + assessmentRunId
                                + "/estimators?testType=INVALID")
                .then()
                .statusCode(400)
                .body("error", containsString("Invalid testType. Must be IID or NON_IID"));
    }

    @Test
    @TestTransaction
    void getEstimators_caseInsensitive_works() {
        UUID assessmentRunId = setupTestDataWithEstimators();

        // Test lowercase "iid"
        given().when()
                .get(
                        ApiProperties.BASE_URL_V1
                                + "/validation/90b-results/"
                                + assessmentRunId
                                + "/estimators?testType=iid")
                .then()
                .statusCode(200)
                .body("$", hasSize(2)); // 2 IID tests

        // Test uppercase "IID"
        given().when()
                .get(
                        ApiProperties.BASE_URL_V1
                                + "/validation/90b-results/"
                                + assessmentRunId
                                + "/estimators?testType=IID")
                .then()
                .statusCode(200)
                .body("$", hasSize(2));

        // Test mixed case "non_iid"
        given().when()
                .get(
                        ApiProperties.BASE_URL_V1
                                + "/validation/90b-results/"
                                + assessmentRunId
                                + "/estimators?testType=non_iid")
                .then()
                .statusCode(200)
                .body("$", hasSize(3)); // 3 Non-IID estimators

        // Test uppercase "NON_IID"
        given().when()
                .get(
                        ApiProperties.BASE_URL_V1
                                + "/validation/90b-results/"
                                + assessmentRunId
                                + "/estimators?testType=NON_IID")
                .then()
                .statusCode(200)
                .body("$", hasSize(3));
    }

    @Test
    @TestTransaction
    void getEstimators_noTestTypeParam_returnsAll() {
        UUID assessmentRunId = setupTestDataWithEstimators();

        given().when()
                .get(
                        ApiProperties.BASE_URL_V1
                                + "/validation/90b-results/"
                                + assessmentRunId
                                + "/estimators")
                .then()
                .statusCode(200)
                .body("$", hasSize(5)) // 3 Non-IID + 2 IID
                .body("[0].testType", notNullValue())
                .body("[0].estimatorName", notNullValue())
                .body("[0].passed", notNullValue());
    }

    @Test
    @TestTransaction
    void getEstimators_filterByIID_returnsOnlyIID() {
        UUID assessmentRunId = setupTestDataWithEstimators();

        given().when()
                .get(
                        ApiProperties.BASE_URL_V1
                                + "/validation/90b-results/"
                                + assessmentRunId
                                + "/estimators?testType=IID")
                .then()
                .statusCode(200)
                .body("$", hasSize(2))
                .body("[0].testType", equalTo("IID"))
                .body("[1].testType", equalTo("IID"))
                .body("[0].estimatorName", equalTo("Chi-Square Test"))
                .body("[1].estimatorName", equalTo("LRS Test"));
    }

    @Test
    @TestTransaction
    void getEstimators_filterByNonIID_returnsOnlyNonIID() {
        UUID assessmentRunId = setupTestDataWithEstimators();

        given().when()
                .get(
                        ApiProperties.BASE_URL_V1
                                + "/validation/90b-results/"
                                + assessmentRunId
                                + "/estimators?testType=NON_IID")
                .then()
                .statusCode(200)
                .body("$", hasSize(3))
                .body("[0].testType", equalTo("NON_IID"))
                .body("[1].testType", equalTo("NON_IID"))
                .body("[2].testType", equalTo("NON_IID"));
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

        given().when()
                .get(
                        ApiProperties.BASE_URL_V1
                                + "/validation/90b-results/"
                                + assessmentRunId
                                + "/estimators")
                .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].entropyEstimate", nullValue()) // NULL, not 0.0
                .body("[0].passed", equalTo(true));
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

        given().when()
                .get(
                        ApiProperties.BASE_URL_V1
                                + "/validation/90b-results/"
                                + assessmentRunId
                                + "/estimators")
                .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].entropyEstimate", equalTo(0.0f)) // 0.0, not null
                .body("[0].passed", equalTo(false));
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

        given().when()
                .get(
                        ApiProperties.BASE_URL_V1
                                + "/validation/90b-results/"
                                + assessmentRunId
                                + "/estimators")
                .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].details.chi_square", equalTo(12.45f))
                .body("[0].details.df", equalTo(10.0f));
    }

    // ==================== Test Helper Methods ====================

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
                        assessmentRunId,
                        TestType.IID,
                        "LRS Test",
                        null,
                        true,
                        null,
                        "LRS test")
                .persist();

        return assessmentRunId;
    }
}
