/* (C)2026 */
package com.ammann.entropy.dto;

import com.ammann.entropy.model.Nist90BEstimatorResult;
import java.util.Map;

/**
 * DTO for individual NIST SP 800-90B estimator test results.
 *
 * <p>Represents a single estimator (e.g., "Collision Test", "Chi-Square Test") within an
 * assessment run. Part of V2a migration to expose ALL 14 estimators (10 Non-IID + 4 IID)
 * with full metadata.
 *
 * <p><b>Entropy Semantics:</b>
 * <ul>
 *   <li>{@code entropyEstimate = null}: non-entropy estimator (for example Chi-Square or LRS)
 *   <li>{@code entropyEstimate = 0.0}: true zero entropy from a degenerate source
 *   <li>{@code -1.0} is never exposed because the upstream sentinel is mapped to {@code null}
 * </ul>
 *
 * @param testType Test type ("IID" or "NON_IID")
 * @param estimatorName Estimator name (e.g., "Collision Test")
 * @param entropyEstimate Entropy estimate in bits per sample (NULL for non-entropy tests)
 * @param passed Whether this estimator test passed
 * @param details Estimator-specific metadata as JSON object (NULL if none)
 * @param description Human-readable description of what this estimator tests
 */
public record NIST90BEstimatorResultDTO(
        String testType,
        String estimatorName,
        Double entropyEstimate,
        boolean passed,
        Map<String, Double> details,
        String description) {

    /**
     * Converts an entity to a DTO.
     *
     * @param entity The entity to convert
     * @return DTO representation
     */
    public static NIST90BEstimatorResultDTO fromEntity(Nist90BEstimatorResult entity) {
        return new NIST90BEstimatorResultDTO(
                entity.testType.name(),
                entity.estimatorName,
                entity.entropyEstimate,
                entity.passed,
                entity.details,
                entity.description);
    }
}
