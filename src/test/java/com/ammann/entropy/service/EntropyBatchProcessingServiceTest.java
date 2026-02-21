/* (C)2026 */
package com.ammann.entropy.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ammann.entropy.dto.BatchProcessingResultDTO;
import com.ammann.entropy.dto.EdgeValidationMetricsDTO;
import com.ammann.entropy.grpc.proto.Ack;
import com.ammann.entropy.grpc.proto.EdgeMetrics;
import com.ammann.entropy.grpc.proto.EntropyBatch;
import com.ammann.entropy.grpc.proto.TDCEvent;
import com.ammann.entropy.model.EntropyData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntropyBatchProcessingServiceTest {

    private final GrpcMappingService mappingService = new GrpcMappingService();
    private final EntropyBatchProcessingService service =
            new EntropyBatchProcessingService(mappingService);

    @Test
    void convertsValidEventsAndSkipsInvalidOnes() {
        long nowUs = System.currentTimeMillis() * 1000;
        EntropyBatch batch =
                EntropyBatch.newBuilder()
                        .setBatchSequence(7)
                        .setSourceId("sensor-x")
                        .addEvents(validEvent(nowUs, nowUs * 2))
                        .addEvents(validEvent(nowUs + 1_000, (nowUs + 1_000) * 2))
                        .addEvents(
                                TDCEvent.newBuilder()
                                        .setRpiTimestampUs(0)
                                        .setTdcTimestampPs(0)
                                        .build()) // invalid
                        .build();

        List<EntropyData> entities = service.toEntities(batch);

        assertThat(entities).hasSize(2);
        assertThat(entities.get(0).batchId).isEqualTo("sensor-x-7");
        assertThat(entities.get(0).sequenceNumber).isEqualTo(1L);
        assertThat(entities.get(1).sequenceNumber).isEqualTo(2L);
    }

    @Test
    void keepsInboundWhitenedEntropyPerEventUnchanged() {
        long nowUs = System.currentTimeMillis() * 1000;
        byte[] firstWhitened = validWhitenedEntropy((byte) 11);
        byte[] secondWhitened = validWhitenedEntropy((byte) 42);
        EntropyBatch batch =
                EntropyBatch.newBuilder()
                        .setBatchSequence(12)
                        .setSourceId("sensor-y")
                        .addEvents(validEvent(nowUs, 123_456_789L, firstWhitened))
                        .addEvents(validEvent(nowUs + 1_000, 223_456_789L, secondWhitened))
                        .build();

        List<EntropyData> entities = service.toEntities(batch);

        assertThat(entities).hasSize(2);
        assertThat(entities.get(0).whitenedEntropy).containsExactly(firstWhitened);
        assertThat(entities.get(1).whitenedEntropy).containsExactly(secondWhitened);
    }

    @Test
    void auditModeDoesNotOverwriteInboundWhitenedEntropy() throws Exception {
        EntropyBatchProcessingService auditService =
                new EntropyBatchProcessingService(mappingService);
        setAuditMode(auditService, true);

        byte[] mismatchingWhitened = new byte[GrpcMappingService.EXPECTED_WHITENED_ENTROPY_BYTES];
        Arrays.fill(mismatchingWhitened, (byte) 0x5A);

        long nowUs = System.currentTimeMillis() * 1000;
        EntropyBatch batch =
                EntropyBatch.newBuilder()
                        .setBatchSequence(13)
                        .setSourceId("sensor-audit")
                        .addEvents(validEvent(nowUs, 777_777_777L, mismatchingWhitened))
                        .build();

        List<EntropyData> entities = auditService.toEntities(batch);

        assertThat(entities).hasSize(1);
        assertThat(entities.getFirst().whitenedEntropy).containsExactly(mismatchingWhitened);
    }

    @Test
    void createAckReflectsResultAndMissingSequences() {
        BatchProcessingResultDTO result =
                new BatchProcessingResultDTO(5L, true, 3, 2, List.of(11L, 12L), 25L, null, null);

        Ack ack = service.createAck(5, result);

        assertThat(ack.getSuccess()).isTrue();
        assertThat(ack.getReceivedSequence()).isEqualTo(5);
        assertThat(ack.getMissingSequencesList()).containsExactly(11, 12);
        assertThat(ack.getMessage()).contains("Successfully processed");
    }

    @Test
    void validateBatchRejectsEmptyBatchButAllowsEdgeWarnings() {
        EntropyBatch emptyBatch = EntropyBatch.newBuilder().setBatchSequence(1).build();
        boolean validEmpty = service.validateBatch(emptyBatch);
        assertThat(validEmpty).isFalse();

        EntropyBatch warningBatch =
                EntropyBatch.newBuilder()
                        .setBatchSequence(2)
                        .addEvents(validEvent(1_000_000, 2_000_000, validWhitenedEntropy((byte) 1)))
                        .setMetrics(
                                EdgeMetrics.newBuilder()
                                        .setQuickShannonEntropy(0.1)
                                        .setFrequencyTestPassed(false)
                                        .setRunsTestPassed(false)
                                        .build())
                        .build();

        assertThat(service.validateBatch(warningBatch)).isTrue();
    }

    @Test
    void extractEdgeMetricsReadsProtoFields() {
        EdgeMetrics metrics =
                EdgeMetrics.newBuilder()
                        .setQuickShannonEntropy(7.5)
                        .setFrequencyTestPassed(true)
                        .setRunsTestPassed(false)
                        .setBiasPpm(12.3)
                        .setAptPassed(true)
                        .setRctPassed(false)
                        .build();

        EntropyBatch batch =
                EntropyBatch.newBuilder()
                        .setBatchSequence(9)
                        .addEvents(validEvent(1_000_000, 2_000_000, validWhitenedEntropy((byte) 2)))
                        .setMetrics(metrics)
                        .setTests(
                                com.ammann.entropy.grpc.proto.TestSummary.newBuilder()
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
    void extractEdgeMetricsReturnsNullWhenBatchHasNoMetrics() {
        EntropyBatch batch =
                EntropyBatch.newBuilder()
                        .setBatchSequence(3)
                        .addEvents(validEvent(1_000, 2_000, validWhitenedEntropy((byte) 3)))
                        .build();

        assertThat(service.extractEdgeMetrics(batch)).isNull();
    }

    @Test
    void createAckUsesErrorMessageOnFailure() {
        BatchProcessingResultDTO failure =
                BatchProcessingResultDTO.failure(8, 0, "validation failed");

        Ack ack = service.createAck(8, failure);

        assertThat(ack.getSuccess()).isFalse();
        assertThat(ack.getMessage()).contains("validation failed");
        assertThat(ack.getMissingSequencesList()).isEmpty();
    }

    private TDCEvent validEvent(long rpiTsUs, long tdcPs) {
        return validEvent(rpiTsUs, tdcPs, validWhitenedEntropy((byte) 7));
    }

    private TDCEvent validEvent(long rpiTsUs, long tdcPs, byte[] whitenedEntropy) {
        return TDCEvent.newBuilder()
                .setRpiTimestampUs(rpiTsUs)
                .setTdcTimestampPs(tdcPs)
                .setChannel(1)
                .setWhitenedEntropy(com.google.protobuf.ByteString.copyFrom(whitenedEntropy))
                .build();
    }

    private byte[] validWhitenedEntropy(byte seed) {
        byte[] bytes = new byte[GrpcMappingService.EXPECTED_WHITENED_ENTROPY_BYTES];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed + i);
        }
        return bytes;
    }

    // toEntities: blank sourceId uses the batch-N prefix.

    @Test
    void toEntities_blankSourceId_usesBatchSequenceInBatchId() {
        long nowUs = System.currentTimeMillis() * 1000;
        EntropyBatch batch =
                EntropyBatch.newBuilder()
                        .setBatchSequence(99)
                        // sourceId is not set and therefore defaults to an empty string.
                        .addEvents(validEvent(nowUs, nowUs * 2))
                        .build();

        List<EntropyData> entities = service.toEntities(batch);

        assertThat(entities).hasSize(1);
        assertThat(entities.getFirst().batchId).isEqualTo("batch-99");
    }

    // validateBatch: batch with metrics where both tests pass and no warning is expected.
    @Test
    void validateBatch_bothMetricsPassing_returnsTrue() {
        long nowUs = System.currentTimeMillis() * 1000;
        EntropyBatch batch =
                EntropyBatch.newBuilder()
                        .setBatchSequence(5)
                        .addEvents(validEvent(nowUs, nowUs * 2, validWhitenedEntropy((byte) 1)))
                        .setMetrics(
                                EdgeMetrics.newBuilder()
                                        .setQuickShannonEntropy(7.9)
                                        .setFrequencyTestPassed(true)
                                        .setRunsTestPassed(true)
                                        .build())
                        .build();

        assertThat(service.validateBatch(batch)).isTrue();
    }

    // createAck: null errorMessage falls back to "Unknown error".
    @Test
    void createAck_nullErrorMessage_usesDefaultMessage() {
        BatchProcessingResultDTO failure =
                BatchProcessingResultDTO.failure(4, 0, null); // null error message

        Ack ack = service.createAck(4, failure);

        assertThat(ack.getSuccess()).isFalse();
        assertThat(ack.getMessage()).isEqualTo("Unknown error");
    }

    // createAck: null missingSequences produces an empty missing list.
    @Test
    void createAck_nullMissingSequences_emptyMissingInAck() {
        // BatchProcessingResultDTO with null missingSequences
        BatchProcessingResultDTO result =
                new BatchProcessingResultDTO(6L, true, 1, 0, null, 10L, null, null);

        Ack ack = service.createAck(6, result);

        assertThat(ack.getSuccess()).isTrue();
        assertThat(ack.getMissingSequencesList()).isEmpty();
    }

    // reasonTag: covers all branches through reflection.
    @Test
    void reasonTag_coversAllBranches() throws Exception {
        Method reasonTag =
                EntropyBatchProcessingService.class.getDeclaredMethod("reasonTag", String.class);
        reasonTag.setAccessible(true);

        assertThat(reasonTag.invoke(service, "invalid whitened_entropy field"))
                .isEqualTo("whitened_entropy");
        assertThat(reasonTag.invoke(service, "missing rpi_timestamp_us value"))
                .isEqualTo("rpi_timestamp_us");
        assertThat(reasonTag.invoke(service, "invalid tdc_timestamp_ps"))
                .isEqualTo("tdc_timestamp_ps");
        assertThat(reasonTag.invoke(service, "timestamp too far in the future"))
                .isEqualTo("time_skew");
        assertThat(reasonTag.invoke(service, "timestamp too old for acceptance"))
                .isEqualTo("time_skew");
        assertThat(reasonTag.invoke(service, "some completely unknown reason")).isEqualTo("other");
    }

    // recordRejectedEvent: non-null meterRegistry path covering the false branch.
    @Test
    void recordRejectedEvent_withMeterRegistry_incrementsCounter() throws Exception {
        EntropyBatchProcessingService svc = new EntropyBatchProcessingService(mappingService);
        Field meterField = EntropyBatchProcessingService.class.getDeclaredField("meterRegistry");
        meterField.setAccessible(true);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        meterField.set(svc, registry);

        long nowUs = System.currentTimeMillis() * 1000;
        // Pass an invalid event (timestamp=0) to trigger rejected counter
        EntropyBatch batch =
                EntropyBatch.newBuilder()
                        .setBatchSequence(55)
                        .setSourceId("test-src")
                        .addEvents(
                                TDCEvent.newBuilder()
                                        .setRpiTimestampUs(0)
                                        .setTdcTimestampPs(0)
                                        .build())
                        .build();

        svc.toEntities(batch);

        // Counter for "other" reason should have been incremented
        double count = registry.get("grpc_events_rejected_total").counter().count();
        assertThat(count).isEqualTo(1.0);
    }

    // auditWhitenedEntropyIfEnabled: null and wrong-length whitened entropy branches.

    @Test
    void auditMode_nullWhitenedEntropy_skipsAudit() throws Exception {
        // Enable compat mode so events without whitened entropy pass validation
        GrpcMappingService compatMapping = new GrpcMappingService();
        Field compatField =
                GrpcMappingService.class.getDeclaredField("allowMissingWhitenedEntropy");
        compatField.setAccessible(true);
        compatField.setBoolean(compatMapping, true);

        EntropyBatchProcessingService auditService =
                new EntropyBatchProcessingService(compatMapping);
        setAuditMode(auditService, true);

        long nowUs = System.currentTimeMillis() * 1000;
        // Empty ByteString in compatibility mode produces null whitenedEntropy and skips audit.
        EntropyBatch batch =
                EntropyBatch.newBuilder()
                        .setBatchSequence(20)
                        .setSourceId("audit-src")
                        .addEvents(
                                TDCEvent.newBuilder()
                                        .setRpiTimestampUs(nowUs)
                                        .setTdcTimestampPs(nowUs * 2)
                                        .setChannel(1)
                                        // Missing whitenedEntropy becomes ByteString.EMPTY and then
                                        // null.
                                        .build())
                        .build();

        List<EntropyData> entities = auditService.toEntities(batch);
        // Compat mode allows the event through; audit skips because whitenedEntropy == null
        assertThat(entities).hasSize(1);
        assertThat(entities.getFirst().whitenedEntropy).isNull();
    }

    @Test
    void auditMode_wrongLengthWhitenedEntropy_skipsAudit() throws Exception {
        // Wrong-size whitened entropy always fails proto validation, so call the private
        // audit method directly with a crafted entity to cover the length != EXPECTED branch.
        EntropyBatchProcessingService auditService =
                new EntropyBatchProcessingService(mappingService);
        setAuditMode(auditService, true);

        byte[] shortWhitened = {0x01, 0x02, 0x03, 0x04}; // 4 bytes, != EXPECTED (32)
        EntropyData entity = new EntropyData();
        entity.whitenedEntropy = shortWhitened;

        TDCEvent protoEvent =
                TDCEvent.newBuilder()
                        .setRpiTimestampUs(1_000_000L)
                        .setTdcTimestampPs(2_000_000L)
                        .setChannel(1)
                        .setWhitenedEntropy(com.google.protobuf.ByteString.copyFrom(shortWhitened))
                        .build();

        // The method should return silently; length mismatch skips the audit path.
        Method auditMethod =
                EntropyBatchProcessingService.class.getDeclaredMethod(
                        "auditWhitenedEntropyIfEnabled",
                        long.class,
                        int.class,
                        TDCEvent.class,
                        EntropyData.class);
        auditMethod.setAccessible(true);
        auditMethod.invoke(auditService, 21L, 0, protoEvent, entity);
        // No assertion needed; verifies the method completes without exception
        assertThat(entity.whitenedEntropy).hasSize(4);
    }

    private void setAuditMode(EntropyBatchProcessingService target, boolean enabled)
            throws Exception {
        Field field = EntropyBatchProcessingService.class.getDeclaredField("whitenedAuditEnabled");
        field.setAccessible(true);
        field.setBoolean(target, enabled);
    }
}
