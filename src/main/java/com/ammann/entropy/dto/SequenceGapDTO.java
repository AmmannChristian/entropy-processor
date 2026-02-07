package com.ammann.entropy.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Represents a range of missing sequence numbers")
public record SequenceGapDTO(
        @Schema(description = "First missing sequence number in this gap")
        Long startSequence,

        @Schema(description = "Last missing sequence number in this gap")
        Long endSequence,

        @Schema(description = "Total count of missing sequences in this gap")
        Long gapSize
) {
    public static SequenceGapDTO of(long start, long end) {
        return new SequenceGapDTO(start, end, end - start + 1);
    }
}
