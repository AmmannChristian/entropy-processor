package com.ammann.entropy.service;

import com.ammann.entropy.dto.EdgeValidationMetricsDTO;
import com.ammann.entropy.grpc.proto.Ack;
import com.ammann.entropy.grpc.proto.EntropyBatch;
import com.ammann.entropy.grpc.proto.TDCEvent;
import com.ammann.entropy.model.EntropyData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class EntropyStreamServiceTest
{

    @Test
    void returnsFailureAckWhenValidationFails() throws
            Exception
    {
        StubBatchProcessor processor = new StubBatchProcessor();
        processor.valid = false;
        StubPersistence persistence = new StubPersistence();
        EntropyStreamService service = new EntropyStreamService(processor, persistence, null, null);

        Ack ack = invokeProcess(service, sampleBatch(1));

        assertThat(ack.getSuccess()).isFalse();
        assertThat(ack.getMessage()).contains("Batch validation failed");
    }

    @Test
    void returnsFailureAckWhenNoValidEntities() throws
            Exception
    {
        StubBatchProcessor processor = new StubBatchProcessor();
        processor.entities = List.of(); // simulate all invalid events filtered out
        StubPersistence persistence = new StubPersistence();
        EntropyStreamService service = new EntropyStreamService(processor, persistence, null, null);

        Ack ack = invokeProcess(service, sampleBatch(2));

        assertThat(ack.getSuccess()).isFalse();
        assertThat(ack.getMessage()).contains("No valid events in batch");
    }

    @Test
    void addsBackpressureFlagWhenQueueHigh() throws
            Exception
    {
        StubBatchProcessor processor = new StubBatchProcessor();
        StubPersistence persistence = new StubPersistence();
        EntropyStreamService service = new EntropyStreamService(processor, persistence, null, null);

        int threshold = backpressureThreshold();
        setQueueSize(service, threshold + 1);

        Ack ack = invokeProcess(service, sampleBatch(3));

        assertThat(ack.getSuccess()).isTrue();
        assertThat(ack.getBackpressure()).isTrue();
        assertThat(ack.getMessage()).contains("Successfully processed");
    }

    @Test
    void controlMessagesCoverAllBranches() throws
            Exception
    {
        EntropyStreamService service = new EntropyStreamService(new StubBatchProcessor(), new StubPersistence(), null, null);
        Method m = EntropyStreamService.class.getDeclaredMethod("handleControlMessage", com.ammann.entropy.grpc.proto.ControlMessage.class);
        m.setAccessible(true);

        var hello = com.ammann.entropy.grpc.proto.ControlMessage.newBuilder()
                .setHello(com.ammann.entropy.grpc.proto.Hello.newBuilder()
                        .setSourceId("gw-1")
                        .setVersion("1.0"))
                .build();
        var health = com.ammann.entropy.grpc.proto.ControlMessage.newBuilder()
                .setHealthReport(com.ammann.entropy.grpc.proto.HealthReport.newBuilder()
                        .setHealthy(true)
                        .setReady(true)
                        .setPoolSize(1)
                        .setLastAckLatencyUs(10))
                .build();
        var ping = com.ammann.entropy.grpc.proto.ControlMessage.newBuilder()
                .setPing(com.ammann.entropy.grpc.proto.Ping.newBuilder()
                        .setTsUs(123))
                .build();
        var config = com.ammann.entropy.grpc.proto.ControlMessage.newBuilder()
                .setConfigUpdate(com.ammann.entropy.grpc.proto.ConfigUpdate.newBuilder()
                        .setMaxRps(1))
                .build();
        var unknown = com.ammann.entropy.grpc.proto.ControlMessage.newBuilder().build();

        assertThat(invokeControl(m, service, hello).hasConfigUpdate()).isTrue();
        assertThat(invokeControl(m, service, health)).isNotNull();
        assertThat(invokeControl(m, service, ping).hasPong()).isTrue();
        assertThat(invokeControl(m, service, config)).isNotNull();
        assertThat(invokeControl(m, service, unknown)).isNotNull();
    }

    @Test
    void subscriberContextEnforcesRateLimit() throws
            Exception
    {
        Class<?> ctxClass = Class.forName("com.ammann.entropy.service.EntropyStreamService$SubscriberContext");
        var ctor = ctxClass.getDeclaredConstructor(String.class);
        ctor.setAccessible(true);
        Object ctx = ctor.newInstance("client-1");

        var maxField = ctxClass.getDeclaredField("maxBatchesPerSecond");
        maxField.setAccessible(true);
        maxField.setInt(ctx, 1); // one per second

        var canSend = ctxClass.getDeclaredMethod("canSendNow");
        canSend.setAccessible(true);

        boolean first = (boolean) canSend.invoke(ctx);
        boolean second = (boolean) canSend.invoke(ctx);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    @Test
    void recordsMetricsOnSuccessfulProcessing() throws
            Exception
    {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StubBatchProcessor processor = new StubBatchProcessor();
        StubPersistence persistence = new StubPersistence();
        EntropyStreamService service = new EntropyStreamService(processor, persistence, registry, null);

        Method init = EntropyStreamService.class.getDeclaredMethod("initMetrics");
        init.setAccessible(true);
        init.invoke(service);

        Ack ack = invokeProcess(service, sampleBatch(10));

        assertThat(ack.getSuccess()).isTrue();
        assertThat(registry.get("batches_received_total").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("batches_processed_success_total").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("events_persisted_total").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("batch_processing_duration_seconds").timer().count()).isEqualTo(1);
    }

    @Test
    void returnsFailureAckOnUnexpectedException() throws
            Exception
    {
        StubBatchProcessor processor = new StubBatchProcessor();
        StubPersistence persistence = new StubPersistence()
        {
            @Override
            public int persistBatch(List<EntropyData> batch)
            {
                throw new RuntimeException("boom");
            }
        };
        EntropyStreamService service = new EntropyStreamService(processor, persistence, null, null);

        Ack ack = invokeProcess(service, sampleBatch(11));

        assertThat(ack.getSuccess()).isFalse();
        assertThat(ack.getMessage()).contains("Internal error");
    }

    @Test
    void streamEntropyRecoversFromStreamFailure()
    {
        EntropyStreamService service = new EntropyStreamService(new StubBatchProcessor(), new StubPersistence(), null, null);
        Multi<EntropyBatch> failing = Multi.createFrom().failure(new RuntimeException("boom"));

        var acks = service.streamEntropy(failing).collect().asList().await().indefinitely();

        assertThat(acks).hasSize(1);
        assertThat(acks.get(0).getSuccess()).isFalse();
        assertThat(acks.get(0).getMessage()).contains("Stream error");
    }

    private Ack invokeProcess(EntropyStreamService service, EntropyBatch batch) throws
            Exception
    {
        Method m = EntropyStreamService.class.getDeclaredMethod("processBatch", EntropyBatch.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Uni<Ack> uni = (Uni<Ack>) m.invoke(service, batch);
        return uni.await().indefinitely();
    }

    private com.ammann.entropy.grpc.proto.ControlMessage invokeControl(
            Method m, EntropyStreamService service, com.ammann.entropy.grpc.proto.ControlMessage msg) throws
            Exception
    {
        return (com.ammann.entropy.grpc.proto.ControlMessage) m.invoke(service, msg);
    }

    private EntropyBatch sampleBatch(int seq)
    {
        return EntropyBatch.newBuilder()
                .setBatchSequence(seq)
                .setSourceId("sensor-x")
                .addEvents(TDCEvent.newBuilder()
                        .setRpiTimestampUs(System.currentTimeMillis() * 1000)
                        .setTdcTimestampPs(2_000_000)
                        .build())
                .build();
    }

    private int backpressureThreshold() throws
            Exception
    {
        Field f = EntropyStreamService.class.getDeclaredField("BACKPRESSURE_THRESHOLD");
        f.setAccessible(true);
        return f.getInt(null);
    }

    private void setQueueSize(EntropyStreamService service, long value) throws
            Exception
    {
        Field f = EntropyStreamService.class.getDeclaredField("processingQueueSize");
        f.setAccessible(true);
        AtomicLong queue = (AtomicLong) f.get(service);
        queue.set(value);
    }

    private static class StubBatchProcessor extends EntropyBatchProcessingService
    {
        boolean valid = true;
        List<EntropyData> entities = List.of(new EntropyData("ts", 1L, 1L));

        StubBatchProcessor()
        {
            super(new GrpcMappingService());
        }

        @Override
        public boolean validateBatch(EntropyBatch batch)
        {
            return valid;
        }

        @Override
        public List<EntropyData> toEntities(EntropyBatch protoBatch)
        {
            return entities;
        }

        @Override
        public EdgeValidationMetricsDTO extractEdgeMetrics(EntropyBatch batch)
        {
            return null;
        }
    }

    private static class StubPersistence extends EntropyDataPersistenceService
    {
        @Override
        public int persistBatch(List<EntropyData> batch)
        {
            return batch.size();
        }
    }
}
