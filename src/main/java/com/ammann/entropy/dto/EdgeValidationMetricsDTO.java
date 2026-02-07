package com.ammann.entropy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Edge validation metrics from Raspberry Pi entropy-tdc-gateway")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EdgeValidationMetricsDTO(
        @Schema(description = "Quick Shannon entropy calculated at edge in bits")
        Double quickShannonEntropy,

        @Schema(description = "Whether frequency test passed at edge")
        Boolean frequencyTestPassed,

        @Schema(description = "Whether runs test passed at edge")
        Boolean runsTestPassed,

        @Schema(description = "Frequency test p-value")
        Double frequencyPValue,

        @Schema(description = "Runs test p-value")
        Double runsPValue,

        @Schema(description = "Whether NIST SP 800-90B Repetition Count Test passed")
        Boolean rctPassed,

        @Schema(description = "Whether NIST SP 800-90B Adaptive Proportion Test passed")
        Boolean aptPassed,

        @Schema(description = "Bias in parts per million")
        Double biasPpm
) {
}