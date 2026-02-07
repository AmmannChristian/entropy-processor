package com.ammann.entropy.health;

import com.ammann.entropy.model.EntropyData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import java.time.Duration;
import java.time.Instant;

/**
 * Readiness health check that verifies TimescaleDB/PostgreSQL connectivity and query performance.
 *
 * <p>Reports DOWN if a basic count query takes longer than 1 second, indicating
 * degraded database performance. Exposes total event count, recent event count,
 * and query latency as health check data.
 */
@Readiness
@ApplicationScoped
public class DatabaseHealthCheck implements HealthCheck {

    @Override
    @ActivateRequestContext
    public HealthCheckResponse call() {
        try {
            Instant start = Instant.now();

            // Test basic connectivity
            long totalEvents = EntropyData.count();

            // Test write performance (simple query)
            long recentCount = EntropyData.count("serverReceived > ?1",
                    Instant.now().minus(Duration.ofHours(1)));

            Duration queryTime = Duration.between(start, Instant.now());
            boolean performanceOk = queryTime.toMillis() < 1000; // < 1 second

            return HealthCheckResponse.named("database-health")
                    .status(performanceOk)
                    .withData("total-events", totalEvents)
                    .withData("recent-events-1h", recentCount)
                    .withData("query-time-ms", queryTime.toMillis())
                    .withData("performance-ok", performanceOk)
                    .withData("database-type", "TimescaleDB/PostgreSQL")
                    .build();

        } catch (Exception e) {
            return HealthCheckResponse.named("database-health")
                    .down()
                    .withData("error", e.getMessage())
                    .withData("database-accessible", false)
                    .build();
        }
    }
}
