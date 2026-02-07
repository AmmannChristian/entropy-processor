/* (C)2026 */
package com.ammann.entropy.health;

import com.ammann.entropy.service.KernelEntropyWriterService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Health check for kernel entropy writer.
 * Verifies /dev/random writer is operational.
 *
 * <p>Status semantics:
 * <ul>
 *   <li>UP: Writer is operational and actively writing</li>
 *   <li>DOWN: Writer failed to initialize (device unavailable or permissions)</li>
 * </ul>
 */
@Readiness
@ApplicationScoped
public class KernelWriterHealthCheck implements HealthCheck {

    @Inject KernelEntropyWriterService kernelWriter;

    @Override
    public HealthCheckResponse call() {
        boolean operational = kernelWriter.isOperational();
        long bytesWritten = kernelWriter.getTotalBytesWritten();

        String status;
        if (operational) {
            status = bytesWritten > 0 ? "ACTIVE" : "INITIALIZED";
        } else {
            status = "FAILED";
        }

        return HealthCheckResponse.named("kernel-entropy-writer")
                .status(operational)
                .withData("operational", operational)
                .withData("bytes-written", bytesWritten)
                .withData("status", status)
                .build();
    }
}
