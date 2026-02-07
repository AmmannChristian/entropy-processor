/* (C)2026 */
package com.ammann.entropy.health;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.MutinyHealthGrpc;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

import java.time.Duration;

/**
 * Health check for NIST SP 800-90B entropy assessment service.
 *
 * <p>Verifies external gRPC service availability using the standard
 * gRPC Health Checking Protocol (grpc.health.v1.Health).
 *
 * <p>Service is considered optional by default - application remains healthy
 * even if NIST service is unreachable.
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code nist.sp80090b.health.required} - Make service mandatory for health (default: false)</li>
 *   <li>{@code nist.sp80090b.health.timeout} - Health check timeout (default: 5s)</li>
 * </ul>
 *
 * @see <a href="https://csrc.nist.gov/publications/detail/sp/800-90b/final">NIST SP 800-90B</a>
 * @see <a href="https://github.com/grpc/grpc/blob/master/doc/health-checking.md">gRPC Health Checking</a>
 */
@Readiness
@ApplicationScoped
public class Nist90BServiceHealthCheck implements HealthCheck {
    private static final String HEALTH_CHECK_NAME = "nist-sp800-90b-service";
    private static final String SERVICE_TYPE = "NIST-SP-800-90B";

    @GrpcClient("sp80090b-health-service")
    MutinyHealthGrpc.MutinyHealthStub healthClient;

    @ConfigProperty(name = "nist.sp80090b.health.required", defaultValue = "false")
    boolean required;

    @ConfigProperty(name = "nist.sp80090b.health.timeout", defaultValue = "5s")
    Duration timeout;

    @ConfigProperty(
            name = "quarkus.grpc.clients.sp80090b-assessment-service.host",
            defaultValue = "nist-sp800-90b")
    String host;

    @ConfigProperty(
            name = "quarkus.grpc.clients.sp80090b-assessment-service.port",
            defaultValue = "50051")
    int port;

    @Override
    public HealthCheckResponse call() {
        long startTime = System.currentTimeMillis();

        HealthCheckResponseBuilder responseBuilder =
                HealthCheckResponse.named(HEALTH_CHECK_NAME)
                        .withData("service-type", SERVICE_TYPE)
                        .withData("host", host)
                        .withData("port", port)
                        .withData("required", required);

        // Check if gRPC client is available
        if (healthClient == null) {
            Log.warn("NIST SP 800-90B gRPC health client not injected");
            return buildResponse(
                    responseBuilder, false, startTime, "gRPC health client not available");
        }

        try {
            // Call standard gRPC Health Check (empty service = overall server health)
            HealthCheckRequest request = HealthCheckRequest.newBuilder().setService("").build();

            io.grpc.health.v1.HealthCheckResponse grpcResponse =
                    healthClient.check(request).await().atMost(timeout);

            boolean serving =
                    grpcResponse.getStatus()
                            == io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING;

            if (serving) {
                Log.debug("NIST SP 800-90B service health check passed");
                return buildResponse(responseBuilder, true, startTime, null);
            } else {
                String status = grpcResponse.getStatus().name();
                Log.warnf("NIST SP 800-90B service not serving: %s", status);
                return buildResponse(
                        responseBuilder, false, startTime, "Service status: " + status);
            }

        } catch (StatusRuntimeException e) {
            Status.Code code = e.getStatus().getCode();
            String message =
                    String.format(
                            "gRPC error: %s - %s", code.name(), e.getStatus().getDescription());

            if (code == Status.Code.UNAVAILABLE) {
                Log.warnf("NIST SP 800-90B service unreachable: %s", e.getStatus());
            } else {
                Log.warnf("NIST SP 800-90B service error: %s", e.getStatus());
            }

            return buildResponse(responseBuilder, false, startTime, message);

        } catch (Exception e) {
            if (isTimeoutException(e)) {
                Log.warn("NIST SP 800-90B service health check timeout");
                return buildResponse(
                        responseBuilder,
                        false,
                        startTime,
                        "Health check timeout after " + timeout.toMillis() + "ms");
            }

            Log.error("Unexpected error in NIST SP 800-90B health check", e);
            return buildResponse(
                    responseBuilder, false, startTime, "Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Build health check response with consistent data fields.
     *
     * @param builder       Response builder with base data
     * @param reachable     Whether service is reachable and serving
     * @param startTimeMs   Health check start time
     * @param errorMessage  Error message if not reachable (null if reachable)
     * @return Configured health check response
     */
    private HealthCheckResponse buildResponse(
            HealthCheckResponseBuilder builder,
            boolean reachable,
            long startTimeMs,
            String errorMessage) {
        long duration = System.currentTimeMillis() - startTimeMs;

        builder.withData("reachable", reachable).withData("last-check-ms", duration);

        if (!reachable && errorMessage != null) {
            builder.withData("error", errorMessage);
        }

        // Determine health status based on reachability and required flag
        // If not required, always return UP (with reachable=false if unavailable)
        // If required, return DOWN when service is unreachable
        if (reachable) {
            return builder.up().build();
        } else if (required) {
            return builder.down().build();
        } else {
            // Service is optional - app stays healthy
            builder.withData("status", "UNAVAILABLE");
            builder.withData("message", "Service unreachable - validation features disabled");
            return builder.up().build();
        }
    }

    /**
     * Check if exception is a timeout exception (handles Mutiny wrapper).
     */
    private boolean isTimeoutException(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
