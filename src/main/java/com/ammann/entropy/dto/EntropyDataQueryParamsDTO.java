/* (C)2026 */
package com.ammann.entropy.dto;

import com.ammann.entropy.model.EntropyData;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.ws.rs.QueryParam;
import java.time.Instant;
import java.util.*;

/**
 * Entity-specific query parameters for EntropyData with filter building and whitelist validation.
 *
 * <p>Supports filtering by:
 * <ul>
 *   <li>batchId - exact match</li>
 *   <li>minQualityScore - minimum quality threshold</li>
 *   <li>channel - exact channel number</li>
 *   <li>from/to - time window (ISO-8601 format)</li>
 *   <li>search - ILIKE search across batchId and sourceAddress</li>
 * </ul>
 * </p>
 *
 * <p>Security: All filters use parameterized queries to prevent SQL injection.
 * Sort fields are validated against a strict whitelist.</p>
 *
 * <p>Performance: Deep pagination without time filters is blocked via validation
 * to prevent full table scans on TimescaleDB hypertables.</p>
 */
public class EntropyDataQueryParamsDTO {

    // Filter Parameters
    @QueryParam("batchId")
    public String batchId;

    @QueryParam("minQualityScore")
    public Double minQualityScore;

    @QueryParam("channel")
    public Integer channel;

    @QueryParam("from")
    public String from; // ISO-8601

    @QueryParam("to")
    public String to; // ISO-8601

    @QueryParam("search")
    public String search; // Full-text search over batchId, sourceAddress

    /**
     * Whitelisted sortable fields (Security Boundary).
     *
     * <p>Only these fields are permitted in ORDER BY clauses to prevent SQL injection
     * via malicious sort parameters.</p>
     */
    private static final Set<String> SORTABLE_FIELDS =
            Set.of(
                    "id",
                    "hwTimestampNs",
                    "serverReceived",
                    "sequenceNumber",
                    "qualityScore",
                    "networkDelayMs",
                    "channel");

    /**
     * Build a parameterized Panache query with filters and sorting.
     *
     * @param sortRequest Optional sort parameters
     * @return A configured PanacheQuery ready for pagination
     */
    public PanacheQuery<EntropyData> buildQuery(SortRequestDTO sortRequest) {
        StringBuilder queryStr = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        // Filter: Batch ID
        if (batchId != null && !batchId.isBlank()) {
            queryStr.append(" AND batchId = :batchId");
            params.put("batchId", batchId);
        }

        // Filter: Quality Score
        if (minQualityScore != null) {
            queryStr.append(" AND qualityScore >= :minQuality");
            params.put("minQuality", minQualityScore);
        }

        // Filter: Channel
        if (channel != null) {
            queryStr.append(" AND channel = :channel");
            params.put("channel", channel);
        }

        // Filter: Time Window
        if (from != null && !from.isBlank()) {
            queryStr.append(" AND serverReceived >= :from");
            params.put("from", Instant.parse(from));
        }

        if (to != null && !to.isBlank()) {
            queryStr.append(" AND serverReceived < :to");
            params.put("to", Instant.parse(to));
        }

        // Search: ILIKE across multiple fields
        if (search != null && !search.isBlank()) {
            queryStr.append(" AND (batchId ILIKE :search OR sourceAddress ILIKE :search)");
            params.put("search", "%" + search + "%");
        }

        // Build ORDER BY (with whitelist validation)
        String orderBy = sortRequest != null ? sortRequest.buildOrderByClause(SORTABLE_FIELDS) : "";

        if (!orderBy.isEmpty()) {
            queryStr.append(" ORDER BY ").append(orderBy);
        } else {
            queryStr.append(" ORDER BY hwTimestampNs DESC"); // Default sort
        }

        return EntropyData.find(queryStr.toString(), params);
    }

    /**
     * Validate query for TimescaleDB performance.
     *
     * <p>Deep pagination without time filters is inefficient on hypertables
     * because TimescaleDB cannot leverage chunk pruning. This validation
     * enforces time window filters for queries beyond page 100.</p>
     *
     * @param pageRequest The pagination parameters
     * @throws IllegalArgumentException if deep pagination is attempted without time filters
     */
    public void validate(PageRequestDTO pageRequest) {
        if (pageRequest.page > 100 && (from == null || to == null)) {
            throw new IllegalArgumentException(
                    "Deep pagination (page > 100) requires time window filters (from/to) for"
                            + " performance");
        }
    }

    @Override
    public String toString() {
        return "EntropyDataQueryParamsDTO{"
                + "batchId='"
                + batchId
                + '\''
                + ", minQualityScore="
                + minQualityScore
                + ", channel="
                + channel
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
