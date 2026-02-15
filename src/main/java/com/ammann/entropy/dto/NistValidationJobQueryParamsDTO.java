/* (C)2026 */
package com.ammann.entropy.dto;

import com.ammann.entropy.enumeration.JobStatus;
import com.ammann.entropy.enumeration.ValidationType;
import com.ammann.entropy.model.NistValidationJob;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.ws.rs.QueryParam;
import java.time.Instant;
import java.util.*;

/**
 * Entity-specific query parameters for NistValidationJob with filter building and whitelist validation.
 *
 * <p>Supports filtering by:
 * <ul>
 *   <li>status - job execution status (enum)</li>
 *   <li>validationType - SP_800_22 or SP_800_90B (enum)</li>
 *   <li>createdBy - username of job creator</li>
 *   <li>from/to - time window for job creation (ISO-8601 format)</li>
 *   <li>search - ILIKE search across createdBy and errorMessage</li>
 * </ul>
 * </p>
 */
public class NistValidationJobQueryParamsDTO {

    @QueryParam("status")
    public JobStatus status;

    @QueryParam("validationType")
    public ValidationType validationType;

    @QueryParam("createdBy")
    public String createdBy;

    @QueryParam("from")
    public String from; // ISO-8601

    @QueryParam("to")
    public String to; // ISO-8601

    @QueryParam("search")
    public String search; // Full-text search over createdBy, errorMessage

    /**
     * Whitelisted sortable fields (Security Boundary).
     */
    private static final Set<String> SORTABLE_FIELDS =
            Set.of(
                    "id",
                    "createdAt",
                    "startedAt",
                    "completedAt",
                    "status",
                    "validationType",
                    "progressPercent");

    /**
     * Build a parameterized Panache query with filters and sorting.
     *
     * @param sortRequest Optional sort parameters
     * @return A configured PanacheQuery ready for pagination
     */
    public PanacheQuery<NistValidationJob> buildQuery(SortRequestDTO sortRequest) {
        StringBuilder queryStr = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (status != null) {
            queryStr.append(" AND status = :status");
            params.put("status", status);
        }

        if (validationType != null) {
            queryStr.append(" AND validationType = :validationType");
            params.put("validationType", validationType);
        }

        if (createdBy != null && !createdBy.isBlank()) {
            queryStr.append(" AND createdBy = :createdBy");
            params.put("createdBy", createdBy);
        }

        if (from != null && !from.isBlank()) {
            queryStr.append(" AND createdAt >= :from");
            params.put("from", Instant.parse(from));
        }

        if (to != null && !to.isBlank()) {
            queryStr.append(" AND createdAt < :to");
            params.put("to", Instant.parse(to));
        }

        if (search != null && !search.isBlank()) {
            queryStr.append(" AND (createdBy ILIKE :search OR errorMessage ILIKE :search)");
            params.put("search", "%" + search + "%");
        }

        String orderBy = sortRequest != null ? sortRequest.buildOrderByClause(SORTABLE_FIELDS) : "";

        if (!orderBy.isEmpty()) {
            queryStr.append(" ORDER BY ").append(orderBy);
        } else {
            queryStr.append(" ORDER BY createdAt DESC");
        }

        return NistValidationJob.find(queryStr.toString(), params);
    }

    @Override
    public String toString() {
        return "NistValidationJobQueryParamsDTO{"
                + "status="
                + status
                + ", validationType="
                + validationType
                + ", createdBy='"
                + createdBy
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
