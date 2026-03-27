/* (C)2026 */
package com.ammann.entropy.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Available filter options for validation jobs and results")
public record ValidationJobFilterOptionsDTO(
        @Schema(description = "Distinct job creators") List<String> createdByValues,
        @Schema(description = "Distinct NIST SP 800-22 test names") List<String> testNames,
        @Schema(description = "Available test suite run IDs with execution timestamps")
                List<RunIdOption> testSuiteRunIds,
        @Schema(description = "Available 800-90B assessment run IDs with execution timestamps")
                List<RunIdOption> assessmentRunIds) {

    @Schema(description = "A run ID with its execution timestamp for display")
    public record RunIdOption(
            @Schema(description = "Run UUID") UUID id,
            @Schema(description = "Earliest execution timestamp of the run") Instant executedAt) {}
}