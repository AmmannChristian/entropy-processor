/* (C)2026 */
package com.ammann.entropy.dto;

import com.ammann.entropy.model.NistTestResult;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.ws.rs.QueryParam;
import java.time.Instant;
import java.util.*;

/**
 * Entity-specific query parameters for NistTestResult with filter building and whitelist validation.
 *
 * <p>Supports filtering by:
 * <ul>
 *   <li>testSuiteRunId - filter by specific test suite run UUID</li>
 *   <li>passed - filter by test pass/fail status</li>
 *   <li>testName - exact test name match</li>
 *   <li>from/to - time window for test execution (ISO-8601 format)</li>
 *   <li>search - ILIKE search across testName</li>
 * </ul>
 * </p>
 */
public class NistTestResultQueryParamsDTO {

    @QueryParam("testSuiteRunId")
    public UUID testSuiteRunId;

    @QueryParam("passed")
    public Boolean passed;

    @QueryParam("testName")
    public String testName;

    @QueryParam("from")
    public String from; // ISO-8601

    @QueryParam("to")
    public String to; // ISO-8601

    @QueryParam("search")
    public String search; // Full-text search over testName

    /**
     * Whitelisted sortable fields (Security Boundary).
     */
    private static final Set<String> SORTABLE_FIELDS =
            Set.of("id", "executedAt", "testName", "passed", "pValue", "chunkIndex");

    /**
     * Build a parameterized Panache query with filters and sorting.
     *
     * @param sortRequest Optional sort parameters
     * @return A configured PanacheQuery ready for pagination
     */
    public PanacheQuery<NistTestResult> buildQuery(SortRequestDTO sortRequest) {
        StringBuilder queryStr = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (testSuiteRunId != null) {
            queryStr.append(" AND testSuiteRunId = :testSuiteRunId");
            params.put("testSuiteRunId", testSuiteRunId);
        }

        if (passed != null) {
            queryStr.append(" AND passed = :passed");
            params.put("passed", passed);
        }

        if (testName != null && !testName.isBlank()) {
            queryStr.append(" AND testName = :testName");
            params.put("testName", testName);
        }

        if (from != null && !from.isBlank()) {
            queryStr.append(" AND executedAt >= :from");
            params.put("from", Instant.parse(from));
        }

        if (to != null && !to.isBlank()) {
            queryStr.append(" AND executedAt < :to");
            params.put("to", Instant.parse(to));
        }

        if (search != null && !search.isBlank()) {
            queryStr.append(" AND testName ILIKE :search");
            params.put("search", "%" + search + "%");
        }

        String orderBy = sortRequest != null ? sortRequest.buildOrderByClause(SORTABLE_FIELDS) : "";

        if (!orderBy.isEmpty()) {
            queryStr.append(" ORDER BY ").append(orderBy);
        } else {
            queryStr.append(" ORDER BY executedAt DESC");
        }

        return NistTestResult.find(queryStr.toString(), params);
    }

    @Override
    public String toString() {
        return "NistTestResultQueryParamsDTO{"
                + "testSuiteRunId="
                + testSuiteRunId
                + ", passed="
                + passed
                + ", testName='"
                + testName
                + '\''
                + ", from='"
                + from
                + '\''
                + ", to='"
                + to
                + '\''
                + ", search='"
                + search
                + '\''
                + '}';
    }
}
