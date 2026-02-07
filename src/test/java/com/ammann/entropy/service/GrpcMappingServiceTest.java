package com.ammann.entropy.service;

import com.ammann.entropy.grpc.proto.TDCEvent;
import com.ammann.entropy.model.EntropyData;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcMappingServiceTest
{

    private final GrpcMappingService service = new GrpcMappingService();

    @Test
    void mapsProtoFieldsToEntity()
    {
        Instant received = Instant.ofEpochMilli(2_000);
        TDCEvent proto = TDCEvent.newBuilder()
                .setRpiTimestampUs(1_000_000)
                .setTdcTimestampPs(2_000_000)
                .setChannel(2)
                .build();

        EntropyData entity = service.toEntity(proto, 42L, received, "batch-1", "source-1");

        assertThat(entity.sequenceNumber).isEqualTo(42L);
        assertThat(entity.hwTimestampNs).isEqualTo(2_000L); // ps/1000 = ns
        assertThat(entity.timestamp).isEqualTo(Instant.ofEpochMilli(1_000).toString());
        assertThat(entity.networkDelayMs).isEqualTo(1_000L);
        assertThat(entity.sourceAddress).isEqualTo("source-1");
        assertThat(entity.whitenedEntropy).isNotEmpty();
    }

    @Test
    void validatesTimestamps()
    {
        long farFuture = System.currentTimeMillis() * 1000 + 120_000_000L; // 120s skew
        TDCEvent invalidFuture = TDCEvent.newBuilder()
                .setRpiTimestampUs(farFuture)
                .setTdcTimestampPs(10_000)
                .build();

        TDCEvent invalidMissing = TDCEvent.newBuilder()
                .setRpiTimestampUs(0)
                .setTdcTimestampPs(0)
                .build();

        assertThat(service.isValidProto(invalidFuture)).isFalse();
        assertThat(service.isValidProto(invalidMissing)).isFalse();
    }

    @Test
    void acceptsReasonableProtoAndRejectsTooOld()
    {
        long recent = System.currentTimeMillis() * 1000 - 30_000_000L; // 30s ago
        TDCEvent recentEvent = TDCEvent.newBuilder()
                .setRpiTimestampUs(recent)
                .setTdcTimestampPs(2_000_000)
                .build();

        TDCEvent tooOld = TDCEvent.newBuilder()
                .setRpiTimestampUs(recent - 26L * 60 * 60 * 1_000_000) // older than 24h
                .setTdcTimestampPs(2_000_000)
                .build();

        assertThat(service.isValidProto(recentEvent)).isTrue();
        assertThat(service.isValidProto(tooOld)).isFalse();
    }

    @Test
    void networkDelayIsNullWhenRpiTimestampMissing()
    {
        Instant received = Instant.ofEpochMilli(5_000);
        TDCEvent proto = TDCEvent.newBuilder()
                .setRpiTimestampUs(0)
                .setTdcTimestampPs(10_000)
                .build();

        EntropyData entity = service.toEntity(proto, 1L, received, "batch-2", "source-2");

        assertThat(entity.networkDelayMs).isNull();
        assertThat(entity.timestamp).isEqualTo(received.toString());
    }
}
