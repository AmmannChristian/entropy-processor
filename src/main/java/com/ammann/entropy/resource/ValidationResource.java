/* (C)2026 */
package com.ammann.entropy.resource;

import com.ammann.entropy.dto.*;
import com.ammann.entropy.enumeration.TestType;
import com.ammann.entropy.model.Nist90BEstimatorResult;
import com.ammann.entropy.model.Nist90BResult;
import com.ammann.entropy.model.NistTestResult;
import com.ammann.entropy.model.NistValidationJob;
import com.ammann.entropy.properties.ApiProperties;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

/**
 * REST resource for querying and managing NIST validation jobs and results.
 *
 * <p>Provides endpoints for:
 * <ul>
 *   <li>Listing validation jobs with filtering and pagination</li>
 *   <li>Viewing SP 800-22 test results</li>
 *   <li>Viewing SP 800-90B assessment results</li>
 * </ul>
 * </p>
 *
 * <p>Requires {@code ADMIN_ROLE} or {@code USER_ROLE} for all endpoints.
 */
@Path(ApiProperties.BASE_URL_V1 + "/validation")
@Tag(name = "Validation API", description = "NIST validation job management and results")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN_ROLE", "USER_ROLE"})
public class ValidationResource {

    private static final Logger LOG = Logger.getLogger(ValidationResource.class);

    @Inject CountCacheServiceDTO cacheService;

    @GET
    @Path("/jobs")
    @Operation(
            summary = "List NIST Validation Jobs",
            description = "Returns paginated list of validation jobs with filtering and sorting")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Jobs retrieved successfully",
                content = @Content(schema = @Schema(implementation = PageResponseDTO.class))),
        @APIResponse(responseCode = "400", description = "Invalid parameters"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response listJobs(
            @BeanParam PageRequestDTO pageRequest,
            @BeanParam SortRequestDTO sortRequest,
            @BeanParam NistValidationJobQueryParamsDTO filters) {

        LOG.debugf(
                "List jobs request: page=%d, size=%d, filters=%s",
                pageRequest.page, pageRequest.size, filters);

        QueryValidatorDTO.validatePageRequest(pageRequest);

        PanacheQuery<NistValidationJob> query = filters.buildQuery(sortRequest);

        // Count total (with caching)
        String cacheKey =
                CountCacheServiceDTO.generateCacheKey(
                        "NistValidationJob",
                        "status",
                        filters.status,
                        "validationType",
                        filters.validationType,
                        "createdBy",
                        filters.createdBy,
                        "from",
                        filters.from,
                        "to",
                        filters.to,
                        "search",
                        filters.search);
        long total = cacheService.getCachedCount(cacheKey, query::count);

        List<NistValidationJob> jobs = query.page(pageRequest.page, pageRequest.size).list();

        List<NistValidationJobDTO> dtos = jobs.stream().map(NistValidationJobDTO::from).toList();

        PageResponseDTO<NistValidationJobDTO> response =
                PageResponseDTO.of(dtos, pageRequest, total);

        LOG.infof(
                "Job query: page=%d, size=%d, total=%d, filters=%s",
                pageRequest.page, pageRequest.size, total, filters);

        return Response.ok(response).build();
    }

    @GET
    @Path("/test-results")
    @Operation(
            summary = "List NIST SP 800-22 Test Results",
            description =
                    "Returns paginated test results with filtering by run, status, and test name")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Test results retrieved successfully",
                content = @Content(schema = @Schema(implementation = PageResponseDTO.class))),
        @APIResponse(responseCode = "400", description = "Invalid parameters"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response listTestResults(
            @BeanParam PageRequestDTO pageRequest,
            @BeanParam SortRequestDTO sortRequest,
            @BeanParam NistTestResultQueryParamsDTO filters) {

        LOG.debugf(
                "List test results request: page=%d, size=%d, filters=%s",
                pageRequest.page, pageRequest.size, filters);

        QueryValidatorDTO.validatePageRequest(pageRequest);

        PanacheQuery<NistTestResult> query = filters.buildQuery(sortRequest);

        // Count total (with caching)
        String cacheKey =
                CountCacheServiceDTO.generateCacheKey(
                        "NistTestResult",
                        "testSuiteRunId",
                        filters.testSuiteRunId,
                        "passed",
                        filters.passed,
                        "testName",
                        filters.testName,
                        "from",
                        filters.from,
                        "to",
                        filters.to,
                        "search",
                        filters.search);
        long total = cacheService.getCachedCount(cacheKey, query::count);

        List<NistTestResult> results = query.page(pageRequest.page, pageRequest.size).list();

        List<NISTTestResultDTO> dtos = results.stream().map(NISTTestResultDTO::from).toList();

        PageResponseDTO<NISTTestResultDTO> response = PageResponseDTO.of(dtos, pageRequest, total);

        LOG.infof(
                "Test results query: page=%d, size=%d, total=%d, runId=%s",
                pageRequest.page, pageRequest.size, total, filters.testSuiteRunId);

        return Response.ok(response).build();
    }

    @GET
    @Path("/90b-results")
    @Operation(
            summary = "List NIST SP 800-90B Assessment Results",
            description = "Returns paginated 90B assessment results with filtering")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "90B results retrieved successfully",
                content = @Content(schema = @Schema(implementation = PageResponseDTO.class))),
        @APIResponse(responseCode = "400", description = "Invalid parameters"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response list90BResults(
            @BeanParam PageRequestDTO pageRequest,
            @BeanParam SortRequestDTO sortRequest,
            @BeanParam Nist90BResultQueryParamsDTO filters) {

        LOG.debugf(
                "List 90B results request: page=%d, size=%d, filters=%s",
                pageRequest.page, pageRequest.size, filters);

        QueryValidatorDTO.validatePageRequest(pageRequest);

        PanacheQuery<Nist90BResult> query = filters.buildQuery(sortRequest);

        // Count total (with caching)
        String cacheKey =
                CountCacheServiceDTO.generateCacheKey(
                        "Nist90BResult",
                        "assessmentRunId",
                        filters.assessmentRunId,
                        "passed",
                        filters.passed,
                        "from",
                        filters.from,
                        "to",
                        filters.to);
        long total = cacheService.getCachedCount(cacheKey, query::count);

        List<Nist90BResult> results = query.page(pageRequest.page, pageRequest.size).list();

        List<NIST90BResultDTO> dtos = results.stream().map(NIST90BResultDTO::from).toList();

        PageResponseDTO<NIST90BResultDTO> response = PageResponseDTO.of(dtos, pageRequest, total);

        LOG.infof(
                "90B results query: page=%d, size=%d, total=%d, assessmentRunId=%s",
                pageRequest.page, pageRequest.size, total, filters.assessmentRunId);

        return Response.ok(response).build();
    }

    @GET
    @Path("/90b-results/{assessmentRunId}/estimators")
    @Operation(
            summary = "Get NIST SP 800-90B Estimator Results",
            description =
                    "Returns detailed estimator results for a specific assessment run. Supports"
                            + " filtering by test type (IID or NON_IID). Part of V2a migration to"
                            + " expose all 14 estimators with full metadata.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Estimators retrieved successfully",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                implementation =
                                                        NIST90BEstimatorResultDTO[].class))),
        @APIResponse(
                responseCode = "400",
                description = "Invalid testType parameter (must be IID or NON_IID)"),
        @APIResponse(responseCode = "404", description = "Assessment run not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    @Transactional
    public Response getEstimatorResults(
            @PathParam("assessmentRunId") UUID assessmentRunId,
            @QueryParam("testType") String testTypeParam) {

        LOG.debugf(
                "Get estimators request: assessmentRunId=%s, testType=%s",
                assessmentRunId, testTypeParam);

        // Verify assessment run exists (404 if not found)
        long assessmentCount = Nist90BResult.count("assessmentRunId", assessmentRunId);
        if (assessmentCount == 0) {
            LOG.warnf("Assessment run not found: %s", assessmentRunId);
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Assessment not found"))
                    .build();
        }

        List<Nist90BEstimatorResult> estimators;

        if (testTypeParam != null) {
            // Validate and parse testType (case-insensitive, 400 if invalid)
            TestType testType;
            try {
                testType = TestType.fromString(testTypeParam);
            } catch (IllegalArgumentException e) {
                LOG.warnf(
                        "Invalid testType parameter: %s (assessmentRunId=%s)",
                        testTypeParam, assessmentRunId);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(
                                Map.of(
                                        "error",
                                        "Invalid testType. Must be IID or NON_IID"
                                                + " (case-insensitive)."))
                        .build();
            }

            // Filter by test type
            estimators =
                    Nist90BEstimatorResult.list(
                            "assessmentRunId = ?1 AND testType = ?2 ORDER BY estimatorName",
                            assessmentRunId,
                            testType);

            LOG.debugf(
                    "Found %d %s estimators for assessmentRunId=%s",
                    estimators.size(), testType, assessmentRunId);
        } else {
            // Return all estimators (both IID and NON_IID)
            estimators =
                    Nist90BEstimatorResult.list(
                            "assessmentRunId = ?1 ORDER BY testType, estimatorName",
                            assessmentRunId);

            LOG.debugf(
                    "Found %d total estimators for assessmentRunId=%s",
                    estimators.size(), assessmentRunId);
        }

        // Convert to DTOs (empty array [] if no results)
        List<NIST90BEstimatorResultDTO> dtos =
                estimators.stream().map(NIST90BEstimatorResultDTO::fromEntity).toList();

        return Response.ok(dtos).build();
    }
}
