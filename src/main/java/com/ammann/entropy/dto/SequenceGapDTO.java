/* (C)2026 */
package com.ammann.entropy.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Represents a range of missing sequence numbers")
/**
 * Closed interval describing consecutive missing sequence numbers.
 *
 * @param startSequence first missing sequence number in the gap
 * @param endSequence last missing sequence number in the gap
 * @param gapSize number of missing sequence numbers in the closed interval
 */
public record SequenceGapDTO(
        @Schema(description = "First missing sequence number in this gap") Long startSequence,
        @Schema(description = "Last missing sequence number in this gap") Long endSequence,
        @Schema(description = "Total count of missing sequences in this gap") Long gapSize) {
    /**
     * Creates a gap DTO and derives the inclusive gap size from bounds.
     *
     * @param start first missing sequence number
     * @param end last missing sequence number
     * @return immutable gap DTO
     */
    public static SequenceGapDTO of(long start, long end) {
        return new SequenceGapDTO(start, end, end - start + 1);
    }
}
