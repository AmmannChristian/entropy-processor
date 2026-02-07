package com.ammann.entropy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "Processing result for a gRPC entropy batch with success status and metrics")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchProcessingResultDTO(
        @Schema(description = "Batch sequence number from gRPC EntropyBatch")
        Long batchSequence,

        @Schema(description = "Whether batch was successfully processed")
        Boolean success,

        @Schema(description = "Number of events received in batch")
        Integer receivedEvents,

        @Schema(description = "Number of events successfully persisted to TimescaleDB")
        Integer persistedEvents,

        @Schema(description = "Missing sequences detected in this batch")
        List<Long> missingSequences,

        @Schema(description = "Processing time in milliseconds")
        Long processingTimeMs,

        @Schema(description = "Error message if processing failed")
        String errorMessage,

        @Schema(description = "Validation metrics from edge device")
        EdgeValidationMetricsDTO edgeMetrics
) {
    /**
     * Creates a successful processing result.
     */
    public static BatchProcessingResultDTO success(
            long batchSequence,
            int receivedEvents,
            int persistedEvents,
            long processingTimeMs) {
        return new BatchProcessingResultDTO(
                batchSequence,
                true,
                receivedEvents,
                persistedEvents,
                List.of(),
                processingTimeMs,
                null,
                null
        );
    }

    /**
     * Creates a failed processing result.
     */
    public static BatchProcessingResultDTO failure(
            long batchSequence,
            int receivedEvents,
            String errorMessage) {
        return new BatchProcessingResultDTO(
                batchSequence,
                false,
                receivedEvents,
                0,
                List.of(),
                0L,
                errorMessage,
                null
        );
    }

    /**
     * Creates result with edge validation metrics.
     */
    public static BatchProcessingResultDTO withMetrics(
            long batchSequence,
            int receivedEvents,
            int persistedEvents,
            long processingTimeMs,
            EdgeValidationMetricsDTO edgeMetrics) {
        return new BatchProcessingResultDTO(
                batchSequence,
                true,
                receivedEvents,
                persistedEvents,
                List.of(),
                processingTimeMs,
                null,
                edgeMetrics
        );
    }
}