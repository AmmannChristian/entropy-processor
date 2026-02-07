package com.ammann.entropy.service;

import com.ammann.entropy.grpc.proto.TDCEvent;
import com.ammann.entropy.model.EntropyData;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.ByteBuffer;
import java.time.Instant;

/**
 * Service for mapping between gRPC protobuf messages and JPA entities.
 *
 * <p>Converts {@code TDCEvent} protobuf messages to {@link EntropyData} entities,
 * performing unit conversions (picoseconds to nanoseconds, microseconds to ISO-8601),
 * network delay calculation, and lightweight whitened entropy derivation.
 */
@ApplicationScoped
public class GrpcMappingService
{

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
        entity.whitenedEntropy = deriveWhitenedEntropy(proto);

        // Convert RPI timestamp (microseconds) to ISO-8601 string
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

        // Calculate network delay: server time - rpi time (in milliseconds)
        long rpiTimestampUs = proto.getRpiTimestampUs();
        long serverTimestampUs = serverReceived.toEpochMilli() * 1000;
        entity.networkDelayMs = rpiTimestampUs > 0
                ? (serverTimestampUs - rpiTimestampUs) / 1000
                : null;

        return entity;
    }

    /**
     * Creates a lightweight whitened byte array based on timestamp data.
     * This is a simple reversible folding intended for downstream NIST tests.
     */
    private byte[] deriveWhitenedEntropy(TDCEvent proto) {
        // Combine TDC and RPI timestamps to build a pseudo-random byte source
        byte[] buffer = ByteBuffer.allocate(16)
                .putLong(proto.getTdcTimestampPs())
                .putLong(proto.getRpiTimestampUs())
                .array();

        byte[] whitened = new byte[buffer.length / 2];
        for (int i = 0; i < whitened.length; i++) {
            whitened[i] = (byte) (buffer[i] ^ buffer[i + whitened.length]);
        }
        return whitened;
    }

    /**
     * Validates that a TDCEvent proto message has valid data.
     *
     * @param proto TDCEvent to validate
     * @return true if valid, false otherwise
     */
    public boolean isValidProto(TDCEvent proto) {
        // Check required fields
        if (proto.getRpiTimestampUs() <= 0) {
            return false;
        }
        if (proto.getTdcTimestampPs() <= 0) {
            return false;
        }

        // Temporal validation - not too far in future/past
        long nowUs = System.currentTimeMillis() * 1000;
        long maxSkewUs = 60_000_000; // 60 seconds
        long rpiTimestampUs = proto.getRpiTimestampUs();

        if (rpiTimestampUs > nowUs + maxSkewUs) {
            return false;
        }
        if (rpiTimestampUs < nowUs - (24 * 60 * 60 * 1_000_000L)) {
            return false;
        }

        return true;
    }
}
