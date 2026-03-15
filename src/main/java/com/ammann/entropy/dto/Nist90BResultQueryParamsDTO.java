/* (C)2026 */
package com.ammann.entropy.dto;

import com.ammann.entropy.model.Nist90BResult;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.ws.rs.QueryParam;
import java.time.Instant;
import java.util.*;

/**
 * Entity-specific query parameters for Nist90BResult with filter building and whitelist validation.
 *
 * <p>Supports filtering by:
 * <ul>
 *   <li>assessmentRunId - filter by specific assessment run UUID</li>
 *   <li>passed - filter by assessment pass/fail status</li>
 *   <li>from/to - time window for assessment execution (ISO-8601 format)</li>
 * </ul>
 * </p>
 */
public class Nist90BResultQueryParamsDTO {

    @QueryParam("assessmentRunId")
    public UUID assessmentRunId;

    @QueryParam("passed")
    public Boolean passed;

    @QueryParam("from")
    public String from; // ISO-8601

    @QueryParam("to")
    public String to; // ISO-8601

    /**
     * When true (default), return only the canonical run-summary rows (isRunSummary = true),
     * ordered by executedAt DESC — one row per completed run.
     * When false, return only per-sample rows (isRunSummary = false), ordered by sampleIndex ASC.
     */
    @QueryParam("summaryOnly")
    public boolean summaryOnly = true;

    /**
     * Whitelisted sortable fields (Security Boundary).
     */
    private static final Set<String> SORTABLE_FIELDS =
            Set.of("id", "executedAt", "passed", "minEntropy", "sampleIndex");

    /**
     * Build a parameterized Panache query with filters and sorting.
     *
     * @param sortRequest Optional sort parameters
     * @return A configured PanacheQuery ready for pagination
     */
    public PanacheQuery<Nist90BResult> buildQuery(SortRequestDTO sortRequest) {
        StringBuilder queryStr = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        // summaryOnly=true (default): canonical run-summary rows only
        // summaryOnly=false: per-sample rows only (for forensic/detail views)
        queryStr.append(" AND isRunSummary = :isRunSummary");
        params.put("isRunSummary", summaryOnly);

        if (assessmentRunId != null) {
            queryStr.append(" AND assessmentRunId = :assessmentRunId");
            params.put("assessmentRunId", assessmentRunId);
        }

        if (passed != null) {
            queryStr.append(" AND passed = :passed");
            params.put("passed", passed);
        }

        if (from != null && !from.isBlank()) {
            queryStr.append(" AND executedAt >= :from");
            params.put("from", Instant.parse(from));
        }

        if (to != null && !to.isBlank()) {
            queryStr.append(" AND executedAt < :to");
            params.put("to", Instant.parse(to));
        }

        String orderBy = sortRequest != null ? sortRequest.buildOrderByClause(SORTABLE_FIELDS) : "";

        if (!orderBy.isEmpty()) {
            queryStr.append(" ORDER BY ").append(orderBy);
        } else if (summaryOnly) {
            queryStr.append(" ORDER BY executedAt DESC");
        } else {
            queryStr.append(" ORDER BY sampleIndex ASC");
        }

        return Nist90BResult.find(queryStr.toString(), params);
    }

    @Override
    public String toString() {
        return "Nist90BResultQueryParamsDTO{"
                + "assessmentRunId="
                + assessmentRunId
                + ", passed="
                + passed
                + ", from='"
                + from
                + '\''
                + ", to='"
                + to
                + '\''
                + ", summaryOnly="
                + summaryOnly
                + '}';
    }
}
