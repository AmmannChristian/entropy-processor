/* (C)2026 */
package com.ammann.entropy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Complete NIST SP 800-22 test suite result")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NISTSuiteResultDTO(
        @Schema(description = "Individual test results for all 15 NIST tests")
                List<NISTTestResultDTO> tests,
        @Schema(description = "Total number of tests executed") Integer totalTests,
        @Schema(description = "Number of tests that passed") Integer passedTests,
        @Schema(description = "Number of tests that failed") Integer failedTests,
        @Schema(description = "Overall pass rate (0.0 - 1.0)") Double overallPassRate,
        @Schema(description = "Whether all tests in the suite passed") Boolean allTestsPassed,
        @Schema(description = "Timestamp when suite was executed") Instant executedAt,
        @Schema(description = "Size of data sample tested (bits)") Long datasetSize,
        @Schema(description = "Time window of source data") TimeWindowDTO dataWindow,
        @Schema(description = "Validation mode: SINGLE_SEQUENCE or MULTI_SEQUENCE_CHI2")
                String validationMode) {
    /**
     * Backwards-compatible constructor without validationMode (defaults to SINGLE_SEQUENCE).
     */
    public NISTSuiteResultDTO(
            List<NISTTestResultDTO> tests,
            Integer totalTests,
            Integer passedTests,
            Integer failedTests,
            Double overallPassRate,
            Boolean allTestsPassed,
            Instant executedAt,
            Long datasetSize,
            TimeWindowDTO dataWindow) {
        this(
                tests,
                totalTests,
                passedTests,
                failedTests,
                overallPassRate,
                allTestsPassed,
                executedAt,
                datasetSize,
                dataWindow,
                "SINGLE_SEQUENCE");
    }

    /**
     * Checks if the entire suite passed.
     * Requires all tests to pass (failedTests == 0 AND allTestsPassed flag is true).
     */
    public boolean suitePassed() {
        return failedTests == 0 && Boolean.TRUE.equals(allTestsPassed);
    }

    /**
     * Returns recommendation based on test results.
     */
    public String getRecommendation() {
        if (suitePassed()) {
            return "Entropy source meets NIST SP 800-22 randomness requirements";
        } else if (failedTests <= 2) {
            return "Minor randomness issues detected - review failed tests and consider re-testing";
        } else {
            return "CRITICAL: Entropy source fails randomness validation - do not use for"
                    + " cryptographic purposes";
        }
    }
}
