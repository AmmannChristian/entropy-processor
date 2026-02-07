package com.ammann.entropy.service;

import com.ammann.entropy.dto.BatchProcessingResultDTO;
import com.ammann.entropy.dto.EdgeValidationMetricsDTO;
import com.ammann.entropy.grpc.proto.Ack;
import com.ammann.entropy.grpc.proto.EdgeMetrics;
import com.ammann.entropy.grpc.proto.EntropyBatch;
import com.ammann.entropy.grpc.proto.TDCEvent;
import com.ammann.entropy.model.EntropyData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Service responsible for converting gRPC {@code EntropyBatch} messages into JPA entities,
 * validating batch integrity, constructing acknowledgment responses, and extracting
 * edge validation metrics.
 *
 * <p>Acts as the bridge between the gRPC data plane and the persistence layer.
 */
@ApplicationScoped
public class EntropyBatchProcessingService
{

    private static final Logger LOG = Logger.getLogger(EntropyBatchProcessingService.class);

    private GrpcMappingService protoConverter;

    @Inject
    public EntropyBatchProcessingService(GrpcMappingService protoConverter){
        this.protoConverter = protoConverter;
    }
    /**
     * Converts gRPC EntropyBatch to list of JPA entities.
     *
     * @param protoBatch gRPC EntropyBatch message
     * @return List of EntropyData entities ready for persistence
     */
    public List<EntropyData> toEntities(EntropyBatch protoBatch)
    {
        Instant serverReceived = Instant.now();
        List<TDCEvent> protoEvents = protoBatch.getEventsList();
        List<EntropyData> entities = new ArrayList<>(protoEvents.size());

        String sourceId = protoBatch.getSourceId();
        String batchId = (sourceId == null || sourceId.isBlank())
                ? "batch-" + protoBatch.getBatchSequence()
                : sourceId + "-" + protoBatch.getBatchSequence();
        long baseSequence = protoBatch.getBatchSequence() * 10000L; // Avoid sequence collision

        for (int i = 0; i < protoEvents.size(); i++) {
            TDCEvent protoEvent = protoEvents.get(i);

            // Validate proto before conversion
            if (!protoConverter.isValidProto(protoEvent)) {
                LOG.warnf("Invalid TDCEvent in batch %d at index %d - skipping",
                        protoBatch.getBatchSequence(), i);
                continue;
            }

            long sequence = baseSequence + i;
            EntropyData entity = protoConverter.toEntity(protoEvent, sequence, serverReceived, batchId, sourceId);
            entities.add(entity);
        }

        LOG.debugf("Converted batch %d: %d proto events -> %d entities",
                protoBatch.getBatchSequence(), protoEvents.size(), entities.size());

        return entities;
    }

    /**
     * Creates ACK response for successful batch processing.
     *
     * @param batchSequence Batch sequence number from gRPC EntropyBatch
     * @param result        Processing result DTO
     * @return gRPC Ack message
     */
    public Ack createAck(long batchSequence, BatchProcessingResultDTO result)
    {
        Ack.Builder ackBuilder = Ack.newBuilder()
                .setSuccess(result.success())
                .setReceivedSequence((int) batchSequence)
                .setReceivedAtUs(Instant.now().toEpochMilli() * 1000);

        if (result.success()) {
            ackBuilder.setMessage(String.format(
                    "Successfully processed %d events", result.persistedEvents()));
        } else {
            ackBuilder.setMessage(result.errorMessage() != null
                    ? result.errorMessage()
                    : "Unknown error");
        }

        // Add missing sequences if any
        if (result.missingSequences() != null && !result.missingSequences().isEmpty()) {
            List<Integer> missingSeqs = result.missingSequences().stream()
                    .map(Long::intValue)
                    .toList();
            ackBuilder.addAllMissingSequences(missingSeqs);
        }

        return ackBuilder.build();
    }

    /**
     * Validates EntropyBatch before processing.
     *
     * @param batch gRPC EntropyBatch to validate
     * @return true if valid, false otherwise
     */
    public boolean validateBatch(EntropyBatch batch)
    {
        if (batch.getEventsCount() == 0) {
            LOG.warnf("Received empty batch: sequence=%d", batch.getBatchSequence());
            return false;
        }

        // Validate metrics if available
        if (batch.hasMetrics()) {
            EdgeMetrics metrics = batch.getMetrics();
            if (!metrics.getFrequencyTestPassed() || !metrics.getRunsTestPassed()) {
                LOG.warnf("Batch %d failed edge validation (freq=%b, runs=%b)",
                        batch.getBatchSequence(),
                        metrics.getFrequencyTestPassed(),
                        metrics.getRunsTestPassed());
                // Log warning but don't reject - cloud can do deeper validation
            }
        }

        return true;
    }

    /**
     * Extracts edge validation metrics from EntropyBatch.
     *
     * @param batch gRPC EntropyBatch
     * @return EdgeValidationMetrics DTO or null if no metrics available
     */
    public EdgeValidationMetricsDTO extractEdgeMetrics(EntropyBatch batch)
    {
        if (!batch.hasMetrics()) {
            return null;
        }

        EdgeMetrics protoMetrics = batch.getMetrics();

        return new EdgeValidationMetricsDTO(
                protoMetrics.getQuickShannonEntropy(),
                protoMetrics.getFrequencyTestPassed(),
                protoMetrics.getRunsTestPassed(),
                batch.hasTests() ? batch.getTests().getFreqPvalue() : null,
                batch.hasTests() ? batch.getTests().getRunsPvalue() : null,
                protoMetrics.hasRctPassed() ? protoMetrics.getRctPassed() : null,
                protoMetrics.hasAptPassed() ? protoMetrics.getAptPassed() : null,
                protoMetrics.hasBiasPpm() ? protoMetrics.getBiasPpm() : null
        );
    }
}
