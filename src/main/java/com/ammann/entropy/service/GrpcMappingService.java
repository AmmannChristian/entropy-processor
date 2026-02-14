package com.ammann.entropy.service;

import com.ammann.entropy.grpc.proto.TDCEvent;
import com.ammann.entropy.model.EntropyData;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service for mapping between gRPC protobuf messages and JPA entities.
 *
 * <p>Converts {@code TDCEvent} protobuf messages to {@link EntropyData} entities,
 * performing unit conversions (picoseconds to nanoseconds, microseconds to ISO-8601),
 * and network delay calculation.
 */
@ApplicationScoped
public class GrpcMappingService
{
    static final int EXPECTED_WHITENED_ENTROPY_BYTES = 32;
    private static final Logger LOG = Logger.getLogger(GrpcMappingService.class);
    private static final AtomicBoolean COMPAT_MODE_LOGGED = new AtomicBoolean(false);

    @ConfigProperty(name = "entropy.processor.grpc.allow-missing-whitened-entropy", defaultValue = "false")
    boolean allowMissingWhitenedEntropy;

    /**
     * Converts a gRPC TDCEvent protobuf message to a JPA EntropyData entity.
     *
     * @param proto gRPC TDCEvent message
     * @param sequence Sequence number for this event (from batch context)
     * @param batchId Gateway-provided batch identifier
     * @param sourceId Source identifier of the gateway
     * @param serverReceived Server reception timestamp
     * @return EntropyData entity ready for persistence
     */
    public EntropyData toEntity(
            TDCEvent proto,
            long sequence,
            Instant serverReceived,
            String batchId,
            String sourceId) {
        EntropyData entity = new EntropyData();

        entity.batchId = batchId;
        entity.sequenceNumber = sequence;
        entity.channel = proto.getChannel();
        entity.rpiTimestampUs = proto.getRpiTimestampUs();
        entity.tdcTimestampPs = proto.getTdcTimestampPs();
        byte[] whitened = proto.getWhitenedEntropy().toByteArray();
        entity.whitenedEntropy = whitened.length == 0 ? null : whitened;

        // Convert gateway ingestion timestamp (microseconds) to ISO-8601 string
        if (proto.getRpiTimestampUs() > 0) {
            entity.timestamp = Instant.ofEpochMilli(proto.getRpiTimestampUs() / 1000).toString();
        } else {
            entity.timestamp = serverReceived.toString();
        }

        // Convert TDC timestamp from picoseconds to nanoseconds
        entity.hwTimestampNs = proto.getTdcTimestampPs() / 1000;

        entity.sourceAddress = sourceId;
        entity.serverReceived = serverReceived;
        entity.createdAt = Instant.now();
        entity.qualityScore = 1.0; // Default, updated later by DataQualityService

        // Calculate ingestion-to-server delay: cloud server reception minus edge gateway ingestion (ms)
        long rpiTimestampUs = proto.getRpiTimestampUs();
        long serverTimestampUs = serverReceived.toEpochMilli() * 1000;
        entity.networkDelayMs = rpiTimestampUs > 0
                ? (serverTimestampUs - rpiTimestampUs) / 1000
                : null;

        return entity;
    }

    /**
     * Validates that a TDCEvent proto message has valid data.
     *
     * @param proto TDCEvent to validate
     * @return true if valid, false otherwise
     */
    public boolean isValidProto(TDCEvent proto) {
        return getValidationError(proto) == null;
    }

    /**
     * Returns a human-readable validation error or null when the proto is valid.
     *
     * <p>Compatibility mode may allow missing whitened entropy for temporary
     * migrations from older gateways.
     */
    public String getValidationError(TDCEvent proto) {
        // Check required fields
        if (proto.getRpiTimestampUs() <= 0) {
            return "missing/invalid rpi_timestamp_us";
        }
        if (proto.getTdcTimestampPs() <= 0) {
            return "missing/invalid tdc_timestamp_ps";
        }
        if (proto.getWhitenedEntropy().isEmpty() && !allowMissingWhitenedEntropy) {
            return "missing whitened_entropy (strict mode)";
        }
        if (proto.getWhitenedEntropy().size() != EXPECTED_WHITENED_ENTROPY_BYTES) {
            if (proto.getWhitenedEntropy().isEmpty() && allowMissingWhitenedEntropy) {
                if (COMPAT_MODE_LOGGED.compareAndSet(false, true)) {
                    LOG.warnf(
                            "Compatibility mode enabled: accepting events without whitened_entropy; "
                                    + "disable via entropy.processor.grpc.allow-missing-whitened-entropy=false after gateway rollout");
                }
            } else {
                return String.format(
                        "invalid whitened_entropy size=%d (expected %d)",
                        proto.getWhitenedEntropy().size(),
                        EXPECTED_WHITENED_ENTROPY_BYTES);
            }
        }

        // Temporal validation - not too far in future/past
        long nowUs = System.currentTimeMillis() * 1000;
        long maxSkewUs = 60_000_000; // 60 seconds
        long rpiTimestampUs = proto.getRpiTimestampUs();

        if (rpiTimestampUs > nowUs + maxSkewUs) {
            return "rpi_timestamp_us too far in future";
        }
        if (rpiTimestampUs < nowUs - (24 * 60 * 60 * 1_000_000L)) {
            return "rpi_timestamp_us too old";
        }

        return null;
    }

    void setAllowMissingWhitenedEntropyForTesting(boolean allow)
    {
        this.allowMissingWhitenedEntropy = allow;
    }
}
