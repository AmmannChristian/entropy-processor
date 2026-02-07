package com.ammann.entropy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "Complete NIST SP 800-22 test suite result")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NISTSuiteResultDTO(
        @Schema(description = "Individual test results for all 15 NIST tests")
        List<NISTTestResultDTO> tests,

        @Schema(description = "Total number of tests executed")
        Integer totalTests,

        @Schema(description = "Number of tests that passed")
        Integer passedTests,

        @Schema(description = "Number of tests that failed")
        Integer failedTests,

        @Schema(description = "Overall pass rate (0.0 - 1.0)")
        Double overallPassRate,

        @Schema(description = "Whether p-value distribution is uniform")
        Boolean uniformityCheck,

        @Schema(description = "Timestamp when suite was executed")
        Instant executedAt,

        @Schema(description = "Size of data sample tested (bits)")
        Long datasetSize,

        @Schema(description = "Time window of source data")
        TimeWindowDTO dataWindow
) {
    /**
     * Checks if the entire suite passed.
     * Requires all tests to pass AND uniformity check to succeed.
     */
    public boolean allTestsPassed() {
        return failedTests == 0 && Boolean.TRUE.equals(uniformityCheck);
    }

    /**
     * Returns recommendation based on test results.
     */
    public String getRecommendation() {
        if (allTestsPassed()) {
            return "Entropy source meets NIST SP 800-22 randomness requirements";
        } else if (failedTests <= 2) {
            return "Minor randomness issues detected - review failed tests and consider re-testing";
        } else {
            return "CRITICAL: Entropy source fails randomness validation - do not use for cryptographic purposes";
        }
    }
}
