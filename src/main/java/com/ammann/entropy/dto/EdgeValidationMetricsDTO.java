/* (C)2026 */
package com.ammann.entropy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Edge validation metrics from Raspberry Pi entropy-tdc-gateway")
@JsonInclude(JsonInclude.Include.NON_NULL)
/**
 * Validation metrics produced at the data-collection edge before backend ingestion.
 *
 * <p>These values are advisory diagnostics and are used to enrich batch processing
 * outcomes with upstream quality signals.
 *
 * @param quickShannonEntropy edge-computed Shannon entropy estimate in bits
 * @param frequencyTestPassed frequency test pass flag
 * @param runsTestPassed runs test pass flag
 * @param frequencyPValue frequency test p-value
 * @param runsPValue runs test p-value
 * @param rctPassed repetition count test pass flag
 * @param aptPassed adaptive proportion test pass flag
 * @param biasPpm measured bias in parts per million
 */
public record EdgeValidationMetricsDTO(
        @Schema(description = "Quick Shannon entropy calculated at edge in bits")
                Double quickShannonEntropy,
        @Schema(description = "Whether frequency test passed at edge") Boolean frequencyTestPassed,
        @Schema(description = "Whether runs test passed at edge") Boolean runsTestPassed,
        @Schema(description = "Frequency test p-value") Double frequencyPValue,
        @Schema(description = "Runs test p-value") Double runsPValue,
        @Schema(description = "Whether NIST SP 800-90B Repetition Count Test passed")
                Boolean rctPassed,
        @Schema(description = "Whether NIST SP 800-90B Adaptive Proportion Test passed")
                Boolean aptPassed,
        @Schema(description = "Bias in parts per million") Double biasPpm) {}
