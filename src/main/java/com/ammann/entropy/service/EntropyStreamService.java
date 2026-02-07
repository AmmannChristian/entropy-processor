/* (C)2026 */
package com.ammann.entropy.service;

import com.ammann.entropy.dto.BatchProcessingResultDTO;
import com.ammann.entropy.dto.EdgeValidationMetricsDTO;
import com.ammann.entropy.grpc.proto.*;
import com.ammann.entropy.model.EntropyData;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.grpc.GrpcService;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jboss.logging.Logger;

/**
 * gRPC service implementation for the {@code EntropyStream} protocol.
 *
 * <p>Provides three RPC methods:
 * <ul>
 *   <li>{@code StreamEntropy} - bidirectional stream for batch ingestion with acknowledgment</li>
 *   <li>{@code SubscribeBatches} - server-to-client stream for broadcasting batches to subscribers</li>
 *   <li>{@code Control} - bidirectional stream for configuration, health reporting, and ping/pong</li>
 * </ul>
 *
 * <p>Implements backpressure signaling and per-subscriber rate limiting.
 * All RPCs require authentication; specific role requirements are documented per method.
 */
@GrpcService
public class EntropyStreamService implements EntropyStream {

    private static final Logger LOG = Logger.getLogger(EntropyStreamService.class);
    private static final int BUFFER_SIZE = 1000;
    private static final int BACKPRESSURE_THRESHOLD = 800;
    private static final int DEFAULT_MAX_BPS = 20;
    private static final long ONE_SECOND_NS = Duration.ofSeconds(1).toNanos();

    EntropyBatchProcessingService batchProcessor;
    EntropyDataPersistenceService persistenceService;
    MeterRegistry meterRegistry;

    SecurityIdentity securityIdentity;

    @Inject
    public EntropyStreamService(
            EntropyBatchProcessingService batchProcessor,
            EntropyDataPersistenceService persistenceService,
            MeterRegistry meterRegistry,
            SecurityIdentity securityIdentity) {
        this.batchProcessor = batchProcessor;
        this.persistenceService = persistenceService;
        this.meterRegistry = meterRegistry;
        this.securityIdentity = securityIdentity;
    }

    // Metrics
    private Counter batchesReceivedCounter;
    private Counter batchesSuccessCounter;
    private Counter batchesFailedCounter;
    private Counter eventsPersistedCounter;
    private Timer batchProcessingTimer;

    // Subscriber registry for broadcast
    private final Map<String, SubscriberContext> subscribers = new ConcurrentHashMap<>();

    // Backpressure tracking
    private final AtomicLong processingQueueSize = new AtomicLong(0);

    /**
     * Initialize metrics on service startup.
     */
    void initMetrics() {
        if (meterRegistry == null) {
            LOG.warn("MeterRegistry not available - metrics disabled");
            return;
        }

        batchesReceivedCounter =
                Counter.builder("batches_received_total")
                        .description("Total number of entropy batches received")
                        .register(meterRegistry);

        batchesSuccessCounter =
                Counter.builder("batches_processed_success_total")
                        .description("Successfully processed batches")
                        .register(meterRegistry);

        batchesFailedCounter =
                Counter.builder("batches_processed_failed_total")
                        .description("Failed batch processing attempts")
                        .register(meterRegistry);

        eventsPersistedCounter =
                Counter.builder("events_persisted_total")
                        .description("Total entropy events persisted to TimescaleDB")
                        .register(meterRegistry);

        batchProcessingTimer =
                Timer.builder("batch_processing_duration_seconds")
                        .description("Batch processing duration")
                        .register(meterRegistry);

        LOG.info("gRPC metrics initialized");
    }

    /**
     * Bidirectional stream: Receives EntropyBatch from Gateway, sends Ack back.
     * <p>
     * Pipeline:
     * 1. Validate batch (schema, edge metrics)
     * 2. Convert proto to JPA entities
     * 3. Persist transactionally to TimescaleDB
     * 4. Send Ack with success/failure status
     * 5. Implement backpressure when queue is full
     *
     * <p>Security: Only accessible by Gateway service with GATEWAY_ROLE
     *
     * @param request Multi stream of EntropyBatch messages
     * @return Multi stream of Ack responses
     */
    @RolesAllowed("GATEWAY_ROLE")
    @Override
    public Multi<Ack> streamEntropy(Multi<EntropyBatch> request) {
        if (meterRegistry != null && batchesReceivedCounter == null) {
            initMetrics();
        }

        LOG.infof(
                "StreamEntropy connection established from principal: %s (roles: %s)",
                principalName(), principalRoles());

        return request.onOverflow()
                .buffer(BUFFER_SIZE)
                .onItem()
                .transformToUniAndConcatenate(
                        batch ->
                                processBatch(batch)
                                        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool()))
                .onFailure()
                .recoverWithItem(
                        throwable -> {
                            LOG.errorf(throwable, "Stream error occurred");
                            return Ack.newBuilder()
                                    .setSuccess(false)
                                    .setMessage("Stream error: " + throwable.getMessage())
                                    .build();
                        })
                .onTermination()
                .invoke(() -> LOG.info("StreamEntropy connection terminated"));
    }

    /**
     * Processes a single EntropyBatch and returns an Ack.
     */
    private Uni<Ack> processBatch(EntropyBatch batch) {
        return Uni.createFrom()
                .item(
                        () -> {
                            long startTime = System.currentTimeMillis();
                            long batchSequence = batch.getBatchSequence();

                            if (batchesReceivedCounter != null) {
                                batchesReceivedCounter.increment();
                            }

                            processingQueueSize.incrementAndGet();

                            try {
                                LOG.debugf(
                                        "Processing batch: sequence=%d, events=%d, source=%s",
                                        batchSequence, batch.getEventsCount(), batch.getSourceId());

                                // Validate batch
                                if (!batchProcessor.validateBatch(batch)) {
                                    if (batchesFailedCounter != null) {
                                        batchesFailedCounter.increment();
                                    }
                                    return createFailureAck(
                                            batchSequence, "Batch validation failed");
                                }

                                // Convert to entities
                                List<EntropyData> entities = batchProcessor.toEntities(batch);
                                if (entities.isEmpty()) {
                                    LOG.warnf("Batch %d produced no valid entities", batchSequence);
                                    return createFailureAck(
                                            batchSequence, "No valid events in batch");
                                }

                                // Persist transactionally
                                int persistedCount = persistWithContext(entities);

                                if (eventsPersistedCounter != null) {
                                    eventsPersistedCounter.increment(persistedCount);
                                }

                                // Extract edge metrics
                                EdgeValidationMetricsDTO edgeMetrics =
                                        batchProcessor.extractEdgeMetrics(batch);

                                // Create success result
                                long processingTimeMs = System.currentTimeMillis() - startTime;
                                BatchProcessingResultDTO result =
                                        BatchProcessingResultDTO.withMetrics(
                                                batchSequence,
                                                batch.getEventsCount(),
                                                persistedCount,
                                                processingTimeMs,
                                                edgeMetrics);

                                if (batchesSuccessCounter != null) {
                                    batchesSuccessCounter.increment();
                                }

                                if (batchProcessingTimer != null) {
                                    batchProcessingTimer.record(
                                            Duration.ofMillis(processingTimeMs));
                                }

                                LOG.infof(
                                        "Batch %d processed: %d events in %dms",
                                        batchSequence, persistedCount, processingTimeMs);

                                // Step 6: Check backpressure
                                Ack ack = batchProcessor.createAck(batchSequence, result);
                                if (shouldApplyBackpressure()) {
                                    ack =
                                            ack.toBuilder()
                                                    .setBackpressure(true)
                                                    .setBackpressureReason(
                                                            "Server queue at "
                                                                    + processingQueueSize.get())
                                                    .build();
                                    LOG.warnf(
                                            "Backpressure active: queue size = %d",
                                            processingQueueSize.get());
                                }

                                // Broadcast to subscribers
                                broadcastToSubscribers(batch);

                                return ack;

                            } catch (Exception e) {
                                LOG.errorf(e, "Failed to process batch %d", batchSequence);
                                if (batchesFailedCounter != null) {
                                    batchesFailedCounter.increment();
                                }
                                return createFailureAck(
                                        batchSequence, "Internal error: " + e.getMessage());

                            } finally {
                                processingQueueSize.decrementAndGet();
                            }
                        });
    }

    @ActivateRequestContext
    public int persistWithContext(List<EntropyData> entities) {
        return persistenceService.persistBatch(entities);
    }

    /**
     * Creates a failure Ack response.
     */
    private Ack createFailureAck(long batchSequence, String errorMessage) {
        return Ack.newBuilder()
                .setSuccess(false)
                .setReceivedSequence((int) batchSequence)
                .setMessage(errorMessage)
                .setReceivedAtUs(Instant.now().toEpochMilli() * 1000)
                .build();
    }

    /**
     * Checks if backpressure should be applied.
     */
    private boolean shouldApplyBackpressure() {
        return processingQueueSize.get() > BACKPRESSURE_THRESHOLD;
    }

    /**
     * Server-to-client stream: Broadcasts batches to subscribing clients.
     * <p>
     * Use cases:
     * - Real-time frontend updates
     * - Secondary analytics services
     * - Monitoring dashboards
     *
     * <p>Security: Accessible by Admin and regular users
     *
     * @param request Subscription request with client ID
     * @return Multi stream of EntropyBatch messages
     */
    @RolesAllowed({"ADMIN_ROLE", "USER_ROLE"})
    @Override
    public Multi<EntropyBatch> subscribeBatches(SubscriptionRequest request) {
        String clientId = request.getClientId();
        LOG.infof(
                "New subscription request from client: %s (principal: %s, roles: %s)",
                clientId, principalName(), principalRoles());

        SubscriberContext context = new SubscriberContext(clientId);
        context.maxBatchesPerSecond = DEFAULT_MAX_BPS;
        subscribers.put(clientId, context);

        return Multi.createFrom()
                .emitter(
                        emitter -> {
                            context.emitter = emitter;

                            emitter.onTermination(
                                    () -> {
                                        subscribers.remove(clientId);
                                        LOG.infof("Client %s unsubscribed", clientId);
                                    });

                            LOG.infof(
                                    "Client %s subscribed successfully (total subscribers: %d)",
                                    clientId, subscribers.size());
                        });
    }

    /**
     * Broadcasts a batch to all active subscribers.
     */
    private void broadcastToSubscribers(EntropyBatch batch) {
        if (subscribers.isEmpty()) {
            return;
        }

        subscribers.forEach(
                (clientId, context) -> {
                    try {
                        if (context.emitter != null && context.canSendNow()) {
                            context.emitter.emit(batch);
                        }
                    } catch (Exception e) {
                        LOG.warnf(e, "Failed to broadcast to client %s", clientId);
                    }
                });
    }

    /**
     * Bidirectional control stream: Configuration updates, health reports, ping/pong.
     * <p>
     * Message types:
     * - Hello: Gateway identification
     * - ConfigUpdate: Adjust gateway parameters
     * - HealthReport: Gateway status monitoring
     * - Ping/Pong: Latency measurement
     *
     * <p>Security: Any authenticated client can access
     *
     * @param request Multi stream of ControlMessage
     * @return Multi stream of ControlMessage responses
     */
    @Authenticated
    @Override
    public Multi<ControlMessage> control(Multi<ControlMessage> request) {
        LOG.infof("Control stream connection established from principal: %s", principalName());

        return request.onItem()
                .transform(this::handleControlMessage)
                .onTermination()
                .invoke(() -> LOG.info("Control stream connection terminated"));
    }

    /**
     * Handles individual control messages.
     */
    private ControlMessage handleControlMessage(ControlMessage message) {
        switch (message.getPayloadCase()) {
            case HELLO -> {
                Hello hello = message.getHello();
                LOG.infof(
                        "Gateway connected: %s (version %s)",
                        hello.getSourceId(), hello.getVersion());
                return ControlMessage.newBuilder()
                        .setConfigUpdate(
                                ConfigUpdate.newBuilder()
                                        .setTargetBatchSize(1840)
                                        .setMaxRps(200)
                                        .build())
                        .build();
            }
            case HEALTH_REPORT -> {
                HealthReport health = message.getHealthReport();
                LOG.infof(
                        "Gateway health: ready=%b, healthy=%b, pool=%d, latency=%dµs",
                        health.getReady(),
                        health.getHealthy(),
                        health.getPoolSize(),
                        health.getLastAckLatencyUs());
                return ControlMessage.newBuilder().build();
            }
            case PING -> {
                Ping ping = message.getPing();
                LOG.debugf("Received ping: %d", ping.getTsUs());
                return ControlMessage.newBuilder()
                        .setPong(Pong.newBuilder().setTsUs(ping.getTsUs()).build())
                        .build();
            }
            case CONFIG_UPDATE -> {
                LOG.info("Received config update from gateway (ignoring)");
                return ControlMessage.newBuilder().build();
            }
            default -> {
                LOG.warnf("Unknown control message type: %s", message.getPayloadCase());
                return ControlMessage.newBuilder().build();
            }
        }
    }

    private String principalName() {
        if (securityIdentity == null || securityIdentity.getPrincipal() == null) {
            return "unknown";
        }
        return securityIdentity.getPrincipal().getName();
    }

    private java.util.Set<String> principalRoles() {
        return securityIdentity == null ? java.util.Set.of() : securityIdentity.getRoles();
    }

    /**
     * Context holder for broadcast subscribers.
     */
    private static class SubscriberContext {
        final String clientId;
        io.smallrye.mutiny.subscription.MultiEmitter<? super EntropyBatch> emitter;
        int maxBatchesPerSecond = DEFAULT_MAX_BPS;
        private final AtomicLong lastEmissionNs = new AtomicLong(0);

        SubscriberContext(String clientId) {
            this.clientId = clientId;
        }

        boolean canSendNow() {
            long now = System.nanoTime();
            long minInterval = ONE_SECOND_NS / Math.max(1, maxBatchesPerSecond);
            long last = lastEmissionNs.get();
            if (now - last < minInterval) {
                return false;
            }
            return lastEmissionNs.compareAndSet(last, now);
        }
    }
}
