package com.ammann.entropy.support;

import com.ammann.entropy.grpc.proto.EdgeMetrics;
import com.ammann.entropy.grpc.proto.EntropyBatch;
import com.ammann.entropy.grpc.proto.TDCEvent;
import com.ammann.entropy.model.EntropyData;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class TestDataFactory {

    private TestDataFactory() {
    }

    public static EntropyData createEntropyEvent(long sequence, long hwTimestampNs, Instant serverReceived) {
        EntropyData data = new EntropyData("ts-" + hwTimestampNs, hwTimestampNs, sequence);
        data.serverReceived = serverReceived;
        data.createdAt = serverReceived;
        data.rpiTimestampUs = hwTimestampNs / 1000; // Convert ns to µs for RPI timestamp
        data.tdcTimestampPs = hwTimestampNs * 1000; // Convert ns to ps for TDC timestamp
        data.networkDelayMs = 2L;
        data.batchId = "batch-" + UUID.randomUUID();
        data.sourceAddress = "test-sensor";
        data.channel = 1;
        data.qualityScore = 1.0;

        return data;
    }

    public static List<EntropyData> buildSequentialEvents(int count, long startHwTimestamp, Instant serverReceived) {
        List<EntropyData> events = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            events.add(createEntropyEvent(i + 1L, startHwTimestamp + (i * 1500L), serverReceived.plusMillis(i * 10L)));
        }

        return events;
    }

    public static EntropyBatch buildEntropyBatch(int batchSequence, List<TDCEvent> events, EdgeMetrics metrics) {
        EntropyBatch.Builder builder = EntropyBatch.newBuilder()
                .setBatchSequence(batchSequence)
                .setSourceId("gateway-1")
                .setBatchTimestampUs(Instant.now().toEpochMilli() * 1000);

        builder.addAllEvents(events);

        if (metrics != null) {
            builder.setMetrics(metrics);
        }

        return builder.build();
    }
}
