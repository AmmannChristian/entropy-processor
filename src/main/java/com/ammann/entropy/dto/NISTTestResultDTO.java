/* (C)2026 */
package com.ammann.entropy.dto;

import com.ammann.entropy.model.NistTestResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "NIST SP 800-22 individual test result")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NISTTestResultDTO(
        @Schema(description = "Test name") String testName,
        @Schema(description = "Whether test passed (p-value >= 0.01)") Boolean passed,
        @Schema(description = "Statistical p-value (0.0 - 1.0)") Double pValue,
        @Schema(
                        description = "Test status",
                        enumeration = {"PASS", "FAIL", "PENDING", "ERROR"})
                String status,
        @Schema(description = "Timestamp when test was executed") Instant executedAt,
        @Schema(description = "Additional test-specific details") String details) {
    /**
     * Converts entity to DTO.
     */
    public static NISTTestResultDTO from(NistTestResult entity) {
        return entity.toDTO();
    }

    /**
     * Creates a test result from pass/fail and p-value.
     */
    public static NISTTestResultDTO create(String testName, boolean passed, double pValue) {
        return new NISTTestResultDTO(
                testName, passed, pValue, passed ? "PASS" : "FAIL", Instant.now(), null);
    }

    /**
     * Creates a test result with error status.
     */
    public static NISTTestResultDTO error(String testName, String errorMessage) {
        return new NISTTestResultDTO(testName, false, null, "ERROR", Instant.now(), errorMessage);
    }
}
