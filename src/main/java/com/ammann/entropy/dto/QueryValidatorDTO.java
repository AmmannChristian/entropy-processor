/* (C)2026 */
package com.ammann.entropy.dto;

import com.ammann.entropy.exception.ValidationException;

/**
 * Security and performance validation utility for query parameters.
 *
 * <p>Enforces hard limits to prevent:
 * <ul>
 *   <li>Memory exhaustion from large page sizes</li>
 *   <li>Performance degradation from deep pagination</li>
 *   <li>DoS attacks via unbounded queries</li>
 * </ul>
 * </p>
 */
public class QueryValidatorDTO {

    private static final int MAX_PAGE_SIZE = 1000;
    private static final int MAX_SAFE_OFFSET = 100_000;

    /**
     * Validate page request parameters for security and performance.
     *
     * @param req The page request to validate
     * @throws ValidationException if validation fails
     */
    public static void validatePageRequest(PageRequestDTO req) {
        if (req.size > MAX_PAGE_SIZE) {
            throw ValidationException.invalidParameter(
                    "size", req.size, "maximum " + MAX_PAGE_SIZE);
        }

        if (req.getOffset() > MAX_SAFE_OFFSET) {
            throw ValidationException.invalidParameter(
                    "page", req.page, "offset too large (use filters to narrow results)");
        }
    }
}
