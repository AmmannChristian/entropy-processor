/* (C)2026 */
package com.ammann.entropy.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Generic response wrapper for paginated data with metadata.
 *
 * <p>Provides complete pagination information to enable UI components
 * to render navigation controls, page indicators, and totals.</p>
 *
 * @param <T> The type of data items in the response
 */
public record PageResponseDTO<T>(
        @JsonProperty("data") List<T> data,
        @JsonProperty("page") int page,
        @JsonProperty("size") int size,
        @JsonProperty("total") long total,
        @JsonProperty("totalPages") int totalPages,
        @JsonProperty("hasNext") boolean hasNext,
        @JsonProperty("hasPrevious") boolean hasPrevious) {
    /**
     * Factory method to create a PageResponseDTO from query results.
     *
     * @param data The data items for this page
     * @param pageRequest The original page request parameters
     * @param total The total number of items across all pages
     * @param <T> The type of data items
     * @return A fully populated PageResponseDTO
     */
    public static <T> PageResponseDTO<T> of(List<T> data, PageRequestDTO pageRequest, long total) {
        int totalPages = (int) Math.ceil((double) total / pageRequest.size);
        return new PageResponseDTO<>(
                data,
                pageRequest.page,
                pageRequest.size,
                total,
                totalPages,
                pageRequest.page < totalPages - 1,
                pageRequest.page > 0);
    }
}
