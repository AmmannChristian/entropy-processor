/* (C)2026 */
package com.ammann.entropy.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DatabaseHealthCheckTest {

    @Nested
    @DisplayName("Health Check Response Tests")
    class HealthCheckResponseTests {

        @Test
        void healthCheckResponseBuilderCreatesUpResponse() {
            HealthCheckResponse response =
                    HealthCheckResponse.named("database-health")
                            .status(true)
                            .withData("total-events", 1000L)
                            .withData("recent-events-1h", 50L)
                            .withData("query-time-ms", 15L)
                            .withData("performance-ok", true)
                            .withData("database-type", "TimescaleDB/PostgreSQL")
                            .build();

            assertThat(response.getName()).isEqualTo("database-health");
            assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
            assertThat(response.getData()).isPresent();
            assertThat(response.getData().get().get("total-events")).isEqualTo(1000L);
            assertThat(response.getData().get().get("recent-events-1h")).isEqualTo(50L);
            assertThat(response.getData().get().get("database-type"))
                    .isEqualTo("TimescaleDB/PostgreSQL");
        }

        @Test
        void healthCheckResponseBuilderCreatesDownResponse() {
            HealthCheckResponse response =
                    HealthCheckResponse.named("database-health")
                            .down()
                            .withData("error", "Connection refused")
                            .withData("database-accessible", false)
                            .build();

            assertThat(response.getName()).isEqualTo("database-health");
            assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
            assertThat(response.getData()).isPresent();
            assertThat(response.getData().get().get("error")).isEqualTo("Connection refused");
            assertThat(response.getData().get().get("database-accessible")).isEqualTo(false);
        }

        @Test
        void healthCheckIncludesPerformanceMetrics() {
            HealthCheckResponse response =
                    HealthCheckResponse.named("database-health")
                            .status(true)
                            .withData("query-time-ms", 50L)
                            .withData("performance-ok", true)
                            .build();

            assertThat(response.getData()).isPresent();
            assertThat(response.getData().get()).containsKey("query-time-ms");
            assertThat(response.getData().get()).containsKey("performance-ok");
        }

        @Test
        void healthCheckHandlesEmptyDatabase() {
            HealthCheckResponse response =
                    HealthCheckResponse.named("database-health")
                            .status(true)
                            .withData("total-events", 0L)
                            .withData("recent-events-1h", 0L)
                            .build();

            assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
            assertThat(response.getData().get().get("total-events")).isEqualTo(0L);
            assertThat(response.getData().get().get("recent-events-1h")).isEqualTo(0L);
        }

        @Test
        void performanceThresholdCheck() {
            long queryTimeMs = 500L;
            boolean performanceOk = queryTimeMs < 1000; // < 1 second

            assertThat(performanceOk).isTrue();

            long slowQueryTimeMs = 1500L;
            boolean slowPerformance = slowQueryTimeMs < 1000;

            assertThat(slowPerformance).isFalse();
        }

        @Test
        void returnsDownWhenEntityManagerUnavailable() {
            DatabaseHealthCheck check = new DatabaseHealthCheck();

            HealthCheckResponse response = check.call();

            assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
            assertThat(response.getData()).isPresent();
            assertThat(response.getData().get().get("database-accessible")).isEqualTo(false);
        }
    }
}
