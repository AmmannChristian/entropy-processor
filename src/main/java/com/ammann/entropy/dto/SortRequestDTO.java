/* (C)2026 */
package com.ammann.entropy.dto;

import jakarta.ws.rs.QueryParam;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Multi-field sort parameters with whitelist validation for SQL injection prevention.
 *
 * <p>Accepts sort parameters in the format: "fieldName:direction" where direction
 * is either "asc" or "desc". Defaults to "asc" if direction is omitted.</p>
 *
 * <p>Examples:
 * <ul>
 *   <li>?sort=createdAt:desc</li>
 *   <li>?sort=createdAt:desc&amp;sort=status:asc</li>
 *   <li>?sort=name (defaults to name:asc)</li>
 * </ul>
 * </p>
 */
public class SortRequestDTO {

    @QueryParam("sort")
    public List<String> sortFields; // e.g., ["createdAt:desc", "status:asc"]

    /**
     * Represents a single sort field with direction.
     *
     * @param field The field name to sort by
     * @param direction The sort direction ("asc" or "desc")
     */
    public record SortField(String field, String direction) {}

    /**
     * Parse the raw sort parameters into structured SortField objects.
     *
     * @return List of parsed sort fields with directions
     */
    public List<SortField> parse() {
        if (sortFields == null || sortFields.isEmpty()) {
            return List.of();
        }

        List<SortField> parsed = new ArrayList<>();
        for (String s : sortFields) {
            String[] parts = s.split(":");
            String field = parts[0];
            String dir = parts.length > 1 ? parts[1] : "asc";
            parsed.add(new SortField(field, dir));
        }
        return parsed;
    }

    /**
     * Build an SQL ORDER BY clause with whitelist validation.
     *
     * <p>CRITICAL: This method enforces strict whitelist validation to prevent
     * SQL injection attacks. Only fields explicitly listed in allowedFields
     * will be accepted. Any attempt to sort by an unlisted field will throw
     * an IllegalArgumentException.</p>
     *
     * @param allowedFields Set of field names that are permitted for sorting
     * @return SQL ORDER BY clause (without the "ORDER BY" keyword), or empty string if no sort
     * @throws IllegalArgumentException if any sort field is not in the whitelist
     */
    public String buildOrderByClause(Set<String> allowedFields) {
        List<SortField> fields = parse();
        if (fields.isEmpty()) {
            return "";
        }

        List<String> clauses = new ArrayList<>();
        for (SortField sf : fields) {
            // CRITICAL: Whitelist validation (SQL Injection Prevention)
            if (!allowedFields.contains(sf.field())) {
                throw new IllegalArgumentException(
                        "Invalid sort field: "
                                + sf.field()
                                + ". Allowed: "
                                + String.join(", ", allowedFields));
            }

            String direction = "desc".equalsIgnoreCase(sf.direction()) ? "DESC" : "ASC";
            clauses.add(sf.field() + " " + direction);
        }

        return String.join(", ", clauses);
    }

    @Override
    public String toString() {
        return "SortRequestDTO{sortFields=" + sortFields + "}";
    }
}
