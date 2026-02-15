/* (C)2026 */
package com.ammann.entropy.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;

/**
 * Reusable DTO for offset-based pagination parameters.
 *
 * <p>Uses offset-based pagination (not cursor-based) to support random page access
 * and compatibility with TimescaleDB hypertables.</p>
 *
 * <p>Default values:
 * <ul>
 *   <li>page = 0 (0-indexed, compatible with Panache)</li>
 *   <li>size = 20 (UI-friendly default)</li>
 *   <li>max size = 1000 (DoS prevention)</li>
 * </ul>
 * </p>
 */
public class PageRequestDTO {

    @QueryParam("page")
    @DefaultValue("0")
    @Min(0)
    public int page;

    @QueryParam("size")
    @DefaultValue("20")
    @Min(1)
    @Max(1000) // Hard limit to prevent memory exhaustion
    public int size;

    /**
     * Calculate the offset for database queries.
     *
     * @return The number of records to skip
     */
    public int getOffset() {
        return page * size;
    }

    /**
     * Get the limit for database queries.
     *
     * @return The number of records to fetch
     */
    public int getLimit() {
        return size;
    }

    /**
     * Backward compatibility helper for legacy 'count' parameter.
     *
     * @param count The legacy count parameter
     * @return A PageRequestDTO configured for backward compatibility
     */
    public static PageRequestDTO fromCount(int count) {
        PageRequestDTO req = new PageRequestDTO();
        req.page = 0;
        req.size = Math.min(count, 1000);
        return req;
    }

    @Override
    public String toString() {
        return "PageRequestDTO{page=" + page + ", size=" + size + "}";
    }
}
