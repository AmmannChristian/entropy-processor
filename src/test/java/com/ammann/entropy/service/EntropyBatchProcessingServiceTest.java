package com.ammann.entropy.service;

import com.ammann.entropy.dto.BatchProcessingResultDTO;
import com.ammann.entropy.dto.EdgeValidationMetricsDTO;
import com.ammann.entropy.grpc.proto.Ack;
import com.ammann.entropy.grpc.proto.EdgeMetrics;
import com.ammann.entropy.grpc.proto.EntropyBatch;
import com.ammann.entropy.grpc.proto.TDCEvent;
import com.ammann.entropy.model.EntropyData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EntropyBatchProcessingServiceTest
{

    private final GrpcMappingService mappingService = new GrpcMappingService();
    private final EntropyBatchProcessingService service = new EntropyBatchProcessingService(mappingService);

    @Test
    void convertsValidEventsAndSkipsInvalidOnes()
    {
        long nowUs = System.currentTimeMillis() * 1000;
        EntropyBatch batch = EntropyBatch.newBuilder()
                .setBatchSequence(7)
                .setSourceId("sensor-x")
                .addEvents(validEvent(nowUs, nowUs * 2))
                .addEvents(validEvent(nowUs + 1_000, (nowUs + 1_000) * 2))
                .addEvents(TDCEvent.newBuilder().setRpiTimestampUs(0).setTdcTimestampPs(0).build()) // invalid
                .build();

        List<EntropyData> entities = service.toEntities(batch);

        assertThat(entities).hasSize(2);
        assertThat(entities.get(0).batchId).isEqualTo("sensor-x-7");
        assertThat(entities.get(0).sequenceNumber).isEqualTo(70_000L);
        assertThat(entities.get(1).sequenceNumber).isEqualTo(70_001L);
    }

    @Test
    void createAckReflectsResultAndMissingSequences()
    {
        BatchProcessingResultDTO result = new BatchProcessingResultDTO(
                5L,
                true,
                3,
                2,
                List.of(11L, 12L),
                25L,
                null,
                null
        );

        Ack ack = service.createAck(5, result);

        assertThat(ack.getSuccess()).isTrue();
        assertThat(ack.getReceivedSequence()).isEqualTo(5);
        assertThat(ack.getMissingSequencesList()).containsExactly(11, 12);
        assertThat(ack.getMessage()).contains("Successfully processed");
    }

    @Test
    void validateBatchRejectsEmptyBatchButAllowsEdgeWarnings()
    {
        EntropyBatch emptyBatch = EntropyBatch.newBuilder().setBatchSequence(1).build();
        boolean validEmpty = service.validateBatch(emptyBatch);
        assertThat(validEmpty).isFalse();

        EntropyBatch warningBatch = EntropyBatch.newBuilder()
                .setBatchSequence(2)
                .addEvents(validEvent(1_000_000, 2_000_000))
                .setMetrics(EdgeMetrics.newBuilder()
                        .setQuickShannonEntropy(0.1)
                        .setFrequencyTestPassed(false)
                        .setRunsTestPassed(false)
                        .build())
                .build();

        assertThat(service.validateBatch(warningBatch)).isTrue();
    }

    @Test
    void extractEdgeMetricsReadsProtoFields()
    {
        EdgeMetrics metrics = EdgeMetrics.newBuilder()
                .setQuickShannonEntropy(7.5)
                .setFrequencyTestPassed(true)
                .setRunsTestPassed(false)
                .setBiasPpm(12.3)
                .setAptPassed(true)
                .setRctPassed(false)
                .build();

        EntropyBatch batch = EntropyBatch.newBuilder()
                .setBatchSequence(9)
                .addEvents(validEvent(1_000_000, 2_000_000))
                .setMetrics(metrics)
                .setTests(com.ammann.entropy.grpc.proto.TestSummary.newBuilder()
                        .setFreqPvalue(0.8)
                        .setRunsPvalue(0.9)
                        .build())
                .build();

        EdgeValidationMetricsDTO dto = service.extractEdgeMetrics(batch);

        assertThat(dto.quickShannonEntropy()).isEqualTo(7.5);
        assertThat(dto.frequencyTestPassed()).isTrue();
        assertThat(dto.runsTestPassed()).isFalse();
        assertThat(dto.biasPpm()).isEqualTo(12.3);
        assertThat(dto.rctPassed()).isFalse();
        assertThat(dto.aptPassed()).isTrue();
        assertThat(dto.frequencyPValue()).isEqualTo(0.8);
        assertThat(dto.runsPValue()).isEqualTo(0.9);
    }

    @Test
    void extractEdgeMetricsReturnsNullWhenBatchHasNoMetrics()
    {
        EntropyBatch batch = EntropyBatch.newBuilder()
                .setBatchSequence(3)
                .addEvents(validEvent(1_000, 2_000))
                .build();

        assertThat(service.extractEdgeMetrics(batch)).isNull();
    }

    @Test
    void createAckUsesErrorMessageOnFailure()
    {
        BatchProcessingResultDTO failure = BatchProcessingResultDTO.failure(8, 0, "validation failed");

        Ack ack = service.createAck(8, failure);

        assertThat(ack.getSuccess()).isFalse();
        assertThat(ack.getMessage()).contains("validation failed");
        assertThat(ack.getMissingSequencesList()).isEmpty();
    }

    private TDCEvent validEvent(long rpiTsUs, long tdcPs)
    {
        return TDCEvent.newBuilder()
                .setRpiTimestampUs(rpiTsUs)
                .setTdcTimestampPs(tdcPs)
                .setChannel(1)
                .build();
    }
}
