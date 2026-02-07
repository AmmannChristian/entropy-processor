/* (C)2026 */
package com.ammann.entropy.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.ammann.entropy.model.EntropyData;
import com.ammann.entropy.support.TestDataFactory;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

@QuarkusTest
class DatabaseHealthCheckIntegrationTest {

    @Test
    @TestTransaction
    void callReturnsUpWithMetrics() {
        EntropyData.deleteAll();
        DatabaseHealthCheck check = new DatabaseHealthCheck();

        Instant now = Instant.now();
        EntropyData.persist(
                TestDataFactory.createEntropyEvent(1, 1_000L, now.minusSeconds(10)),
                TestDataFactory.createEntropyEvent(2, 2_000L, now.minusSeconds(5)));

        HealthCheckResponse response = check.call();

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(response.getData()).isPresent();
        assertThat(response.getData().get().get("total-events")).isEqualTo(2L);
        assertThat(response.getData().get().get("recent-events-1h")).isEqualTo(2L);
    }
}
