/* (C)2026 */
package com.ammann.entropy.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ammann.entropy.model.EntropyData;
import com.ammann.entropy.support.TestDataFactory;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class EntropyDataPersistenceServiceTest {

    @Inject EntropyDataPersistenceService service;

    @Test
    @TestTransaction
    void persistBatchReturnsZeroForEmptyInput() {
        EntropyData.deleteAll();

        int persisted = service.persistBatch(List.of());

        assertThat(persisted).isZero();
        assertThat(EntropyData.count()).isZero();
    }

    @Test
    @TestTransaction
    void persistBatchPersistsEntities() {
        EntropyData.deleteAll();
        List<EntropyData> batch =
                TestDataFactory.buildSequentialEvents(3, 1_000_000L, Instant.now());

        int persisted = service.persistBatch(batch);

        assertThat(persisted).isEqualTo(3);
        assertThat(EntropyData.count()).isEqualTo(3L);
    }

    @Test
    @TestTransaction
    void persistBatchHandlesLargeBatch() {
        EntropyData.deleteAll();
        List<EntropyData> batch =
                TestDataFactory.buildSequentialEvents(105, 5_000_000L, Instant.now());

        int persisted = service.persistBatch(batch);

        assertThat(persisted).isEqualTo(105);
        assertThat(EntropyData.count()).isEqualTo(105L);
    }

    @Test
    @TestTransaction
    void persistIgnoresNullEvent() {
        EntropyData.deleteAll();

        service.persist(null);

        assertThat(EntropyData.count()).isZero();
    }

    @Test
    @TestTransaction
    void persistStoresEntity() {
        EntropyData.deleteAll();
        EntropyData event = TestDataFactory.createEntropyEvent(1, 1_000_000L, Instant.now());

        service.persist(event);

        assertThat(EntropyData.count()).isEqualTo(1L);
    }
}
