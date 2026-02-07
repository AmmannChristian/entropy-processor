/* (C)2026 */
package com.ammann.entropy.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.MutinyHealthGrpc;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

class Nist22ServiceHealthCheckTest {

    @Test
    void returnsUpWhenServing() {
        Nist22ServiceHealthCheck check = new Nist22ServiceHealthCheck();
        check.required = true;
        check.timeout = Duration.ofSeconds(1);
        check.host = "nist-host";
        check.port = 50051;
        check.healthClient = mock(MutinyHealthGrpc.MutinyHealthStub.class);

        io.grpc.health.v1.HealthCheckResponse grpcResponse =
                io.grpc.health.v1.HealthCheckResponse.newBuilder()
                        .setStatus(io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING)
                        .build();
        when(check.healthClient.check(any(HealthCheckRequest.class)))
                .thenReturn(Uni.createFrom().item(grpcResponse));

        HealthCheckResponse response = check.call();

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(response.getData()).isPresent();
        assertThat(response.getData().get().get("reachable")).isEqualTo(true);
    }

    @Test
    void returnsDownWhenNotServingAndRequired() {
        Nist22ServiceHealthCheck check = new Nist22ServiceHealthCheck();
        check.required = true;
        check.timeout = Duration.ofSeconds(1);
        check.host = "nist-host";
        check.port = 50051;
        check.healthClient = mock(MutinyHealthGrpc.MutinyHealthStub.class);

        io.grpc.health.v1.HealthCheckResponse grpcResponse =
                io.grpc.health.v1.HealthCheckResponse.newBuilder()
                        .setStatus(io.grpc.health.v1.HealthCheckResponse.ServingStatus.NOT_SERVING)
                        .build();
        when(check.healthClient.check(any(HealthCheckRequest.class)))
                .thenReturn(Uni.createFrom().item(grpcResponse));

        HealthCheckResponse response = check.call();

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
        assertThat(response.getData()).isPresent();
        assertThat(response.getData().get().get("reachable")).isEqualTo(false);
    }

    @Test
    void returnsUpWhenUnavailableAndOptional() {
        Nist22ServiceHealthCheck check = new Nist22ServiceHealthCheck();
        check.required = false;
        check.timeout = Duration.ofSeconds(1);
        check.host = "nist-host";
        check.port = 50051;
        check.healthClient = mock(MutinyHealthGrpc.MutinyHealthStub.class);

        when(check.healthClient.check(any(HealthCheckRequest.class)))
                .thenReturn(
                        Uni.createFrom().failure(new StatusRuntimeException(Status.UNAVAILABLE)));

        HealthCheckResponse response = check.call();

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(response.getData()).isPresent();
        assertThat(response.getData().get().get("reachable")).isEqualTo(false);
        assertThat(response.getData().get().get("status")).isEqualTo("UNAVAILABLE");
    }

    @Test
    void returnsTimeoutMessageWhenHealthCheckTimesOut() {
        Nist22ServiceHealthCheck check = new Nist22ServiceHealthCheck();
        check.required = false;
        check.timeout = Duration.ofMillis(50);
        check.host = "nist-host";
        check.port = 50051;
        check.healthClient = mock(MutinyHealthGrpc.MutinyHealthStub.class);

        when(check.healthClient.check(any(HealthCheckRequest.class)))
                .thenReturn(Uni.createFrom().failure(new TimeoutException("timeout")));

        HealthCheckResponse response = check.call();

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(response.getData()).isPresent();
        assertThat(response.getData().get().get("error").toString())
                .contains("Health check timeout");
    }
}
