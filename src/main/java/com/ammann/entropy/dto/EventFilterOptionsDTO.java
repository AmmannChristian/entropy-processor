/* (C)2026 */
package com.ammann.entropy.dto;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Available filter options for entropy events")
public record EventFilterOptionsDTO(
        @Schema(description = "Distinct batch IDs present in the data") List<String> batchIds,
        @Schema(description = "Distinct hardware channels present in the data") List<Integer> channels) {}