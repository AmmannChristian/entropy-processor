package com.ammann.entropy.service;

import com.ammann.entropy.model.EntropyData;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Service for persisting {@link EntropyData} entities to TimescaleDB.
 *
 * <p>Provides both single-entity and batch persistence. The batch method uses an
 * EntityManager flush/clear pattern to keep memory usage bounded when processing
 * high-throughput gRPC event streams.
 */
@ApplicationScoped
public class EntropyDataPersistenceService {

    private static final Logger LOG = Logger.getLogger(EntropyDataPersistenceService.class);
    private static final int FLUSH_BATCH_SIZE = 100;

    /**
     * Batch persist optimization for high-throughput gRPC event streams.
     * <p>
     * Uses EntityManager flush/clear pattern to avoid OutOfMemoryError
     * when persisting large batches (e.g., 1840 events).
     * <p>
     * Flushes to database every 100 entities to keep memory footprint low.
     *
     * @param batch List of EntropyData entities to persist
     * @return Number of entities successfully persisted
     */
    @Transactional
    public int persistBatch(List<EntropyData> batch) {
        if (batch == null || batch.isEmpty()) {
            LOG.warn("Attempted to persist empty batch");
            return 0;
        }

        EntityManager em = Panache.getEntityManager();
        int count = 0;
        long startTime = System.currentTimeMillis();

        try {
            for (EntropyData event : batch) {
                em.persist(event);
                count++;

                // Flush and clear every FLUSH_BATCH_SIZE entities to free memory
                if (count % FLUSH_BATCH_SIZE == 0) {
                    em.flush();
                    em.clear();
                    LOG.debugf("Flushed %d events to database", count);
                }
            }

            // Final flush for remaining entities
            em.flush();

            long duration = System.currentTimeMillis() - startTime;
            LOG.infof("Successfully persisted %d events in %dms (%.1f events/sec)",
                    count, duration, count * 1000.0 / duration);

            return count;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to persist batch after %d events", count);
            throw e;
        }
    }

    /**
     * Persists a single EntropyData entity.
     * <p>
     * Use {@link #persistBatch(List)} for better performance with multiple events.
     *
     * @param event EntropyData entity to persist
     */
    @Transactional
    public void persist(EntropyData event) {
        if (event == null) {
            LOG.warn("Attempted to persist null event");
            return;
        }

        event.persist();
        LOG.debugf("Persisted single event: sequence=%d, hwTimestampNs=%d",
                event.sequenceNumber, event.hwTimestampNs);
    }
}
