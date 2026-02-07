/* (C)2026 */
package com.ammann.entropy.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ammann.entropy.service.KernelEntropyWriterService;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

class KernelWriterHealthCheckTest {

    @Test
    void reportsActiveWhenOperationalAndWritten() {
        KernelEntropyWriterService writer = mock(KernelEntropyWriterService.class);
        when(writer.isOperational()).thenReturn(true);
        when(writer.getTotalBytesWritten()).thenReturn(128L);

        KernelWriterHealthCheck check = new KernelWriterHealthCheck();
        check.kernelWriter = writer;

        HealthCheckResponse response = check.call();

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(response.getData()).isPresent();
        assertThat(response.getData().get().get("status")).isEqualTo("ACTIVE");
        assertThat(response.getData().get().get("bytes-written")).isEqualTo(128L);
    }

    @Test
    void reportsInitializedWhenOperationalButNoBytes() {
        KernelEntropyWriterService writer = mock(KernelEntropyWriterService.class);
        when(writer.isOperational()).thenReturn(true);
        when(writer.getTotalBytesWritten()).thenReturn(0L);

        KernelWriterHealthCheck check = new KernelWriterHealthCheck();
        check.kernelWriter = writer;

        HealthCheckResponse response = check.call();

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(response.getData()).isPresent();
        assertThat(response.getData().get().get("status")).isEqualTo("INITIALIZED");
    }

    @Test
    void reportsFailedWhenNotOperational() {
        KernelEntropyWriterService writer = mock(KernelEntropyWriterService.class);
        when(writer.isOperational()).thenReturn(false);
        when(writer.getTotalBytesWritten()).thenReturn(0L);

        KernelWriterHealthCheck check = new KernelWriterHealthCheck();
        check.kernelWriter = writer;

        HealthCheckResponse response = check.call();

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
        assertThat(response.getData()).isPresent();
        assertThat(response.getData().get().get("status")).isEqualTo("FAILED");
    }
}
