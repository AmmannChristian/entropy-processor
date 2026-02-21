/* (C)2026 */
package com.ammann.entropy.service;

import com.ammann.entropy.model.EntropyData;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Writes validated entropy bytes to the Linux kernel entropy pool.
 *
 * <p>Implementation Notes:
 * <ul>
 *   <li>Uses simple write() to /dev/random (mixing, no credited entropy)</li>
 *   <li>For credited entropy, would need RNDADDENTROPY ioctl + CAP_SYS_ADMIN</li>
 *   <li>Scheduled execution every 2-5 seconds for optimal pool feeding</li>
 *   <li>drand reads from /dev/urandom (benefits from pool mixing)</li>
 *   <li>Non-overlapping execution (SKIP concurrent runs)</li>
 * </ul>
 *
 * <p>LoE Compatibility:
 * <ul>
 *   <li>Transparent OS-level integration (no drand protocol changes)</li>
 *   <li>Uses upstream drand binary (not forked)</li>
 *   <li>Documents that entropy is mixed, not credited</li>
 *   <li>Only writes real whitened entropy (no deterministic padding)</li>
 * </ul>
 *
 * <p>Deployment Requirements:
 * <ul>
 *   <li>Container must have access to /dev/random (device mapping)</li>
 *   <li>Requires CAP_DAC_OVERRIDE capability for write access</li>
 *   <li>Must set kernel.entropy.writer.enabled=true in production</li>
 * </ul>
 */
@ApplicationScoped
public class KernelEntropyWriterService {

    private static final String DEV_RANDOM = "/dev/random";
    private static final int BYTES_PER_WRITE = 512; // 4096 bits
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MS = 1000;

    @ConfigProperty(name = "kernel.entropy.writer.enabled", defaultValue = "false")
    boolean writerEnabled;

    @ConfigProperty(name = "kernel.entropy.writer.device", defaultValue = DEV_RANDOM)
    String targetDevice;

    @Inject MeterRegistry meterRegistry;

    private FileOutputStream deviceStream;
    private Counter bytesWrittenCounter;
    private Counter writeFailuresCounter;
    private Counter writeRetriesCounter;
    private final AtomicLong totalBytesWritten = new AtomicLong(0); // Thread-safe
    private volatile Instant lastSuccessfulInjectionTimestamp = null;
    private volatile boolean initialized = false;

    /**
     * Initialize file handle and metrics.
     * Runs once at application startup.
     */
    @PostConstruct
    void init() {
        if (!writerEnabled) {
            Log.info("Kernel entropy writer is disabled (kernel.entropy.writer.enabled=false)");
            return;
        }

        try {
            // Open device with append mode
            this.deviceStream = new FileOutputStream(targetDevice, true);
            this.initialized = true;
            Log.infof("Opened %s for kernel entropy mixing (no credited entropy)", targetDevice);
        } catch (IOException e) {
            Log.errorf(
                    e,
                    "Cannot open %s - continuing without kernel entropy injection",
                    targetDevice);
            Log.warn("Ensure container has device mapping and CAP_DAC_OVERRIDE capability");
            this.initialized = false;
        }

        // Initialize metrics (safe even if meterRegistry is null)
        initMetrics();
    }

    /**
     * Clean shutdown: flush and close file handle.
     */
    @PreDestroy
    void cleanup() {
        if (deviceStream != null) {
            try {
                deviceStream.flush();
                deviceStream.close();
                Log.infof(
                        "Closed %s (total bytes written: %d)",
                        targetDevice, totalBytesWritten.get());
            } catch (IOException e) {
                Log.warn("Error closing device stream", e);
            }
        }
    }

    /**
     * Scheduled kernel entropy feeding.
     * <p>
     * Uses @ActivateRequestContext for lightweight context activation (read-only Panache query).
     *
     * <p>Execution Flow:
     * <ol>
     *   <li>Load recent whitened entropy from database (last 15 seconds)</li>
     *   <li>Extract up to 512 bytes from whitened entropy pool</li>
     *   <li>Write to /dev/random (with retry logic)</li>
     *   <li>Update Prometheus metrics</li>
     * </ol>
     *
     * <p>Thread Safety:
     * <ul>
     *   <li>ConcurrentExecution.SKIP prevents overlapping runs</li>
     *   <li>AtomicLong for totalBytesWritten counter</li>
     * </ul>
     */
    @Scheduled(
            every = "${kernel.entropy.writer.interval}",
            concurrentExecution = ConcurrentExecution.SKIP)
    @ActivateRequestContext
    public void feedKernelEntropy() {
        if (!initialized || !writerEnabled) {
            return;
        }

        try {
            // Get recent whitened entropy
            Instant now = Instant.now();
            Instant start = now.minus(15, ChronoUnit.SECONDS);

            List<EntropyData> recentEvents = EntropyData.findInTimeWindow(start, now);

            if (recentEvents.isEmpty()) {
                Log.debug("No recent entropy events - skipping kernel write");
                return;
            }

            // Extract whitened bytes (WITHOUT deterministic padding)
            byte[] entropyBytes = extractWhitenedEntropy(recentEvents);

            if (entropyBytes.length == 0) {
                Log.debug("No whitened entropy available - skipping kernel write");
                return;
            }

            // Write to kernel with retry logic
            boolean success = writeWithRetry(entropyBytes);

            if (success) {
                totalBytesWritten.addAndGet(entropyBytes.length);
                incrementCounter(bytesWrittenCounter, entropyBytes.length);
                lastSuccessfulInjectionTimestamp = Instant.now();
                Log.debug(
                        "Wrote "
                                + entropyBytes.length
                                + " bytes to "
                                + targetDevice
                                + " (total: "
                                + totalBytesWritten.get()
                                + ")");
            }

        } catch (Exception e) {
            Log.error("Unexpected error in kernel entropy writer", e);
            incrementCounter(writeFailuresCounter, 1);
        }
    }

    /**
     * Write entropy with exponential backoff retry logic.
     *
     * @param data Entropy bytes to write
     * @return true if write succeeded, false if all retries exhausted
     */
    private boolean writeWithRetry(byte[] data) {
        for (int attempt = 0; attempt < MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                deviceStream.write(data);
                deviceStream.flush();
                return true;

            } catch (IOException e) {
                incrementCounter(writeRetriesCounter, 1);
                Log.warnf(
                        "Write to %s failed (attempt %d/%d): %s",
                        targetDevice, attempt + 1, MAX_RETRY_ATTEMPTS, e.getMessage());

                if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                    // Exponential backoff
                    long backoffMs = RETRY_BACKOFF_MS * (1L << attempt);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        incrementCounter(writeFailuresCounter, 1);
        Log.errorf("Failed to write to %s after %d attempts", targetDevice, MAX_RETRY_ATTEMPTS);
        return false;
    }

    /**
     * Extract whitened entropy from EntropyData events.
     *
     * <p>IMPORTANT: Only uses real whitened entropy, NO deterministic padding.
     * If insufficient whitened data is available, returns fewer bytes.
     * This ensures only genuine entropy is written to kernel pool.
     *
     * @param events List of EntropyData with whitenedEntropy field
     * @return Byte array of available whitened entropy (may be shorter than maxBytes)
     */
    private byte[] extractWhitenedEntropy(List<EntropyData> events) {

        ByteBuffer buffer = ByteBuffer.allocate(KernelEntropyWriterService.BYTES_PER_WRITE);

        for (EntropyData event : events) {
            if (buffer.remaining() == 0) break;

            byte[] whitened = event.whitenedEntropy;
            if (whitened != null && whitened.length > 0) {
                int copyLength = Math.min(whitened.length, buffer.remaining());
                buffer.put(whitened, 0, copyLength);
            }
        }

        // Return only the bytes we actually filled (no padding!)
        int bytesCollected = buffer.position();
        byte[] result = new byte[bytesCollected];
        buffer.flip();
        buffer.get(result);

        if (bytesCollected < KernelEntropyWriterService.BYTES_PER_WRITE) {
            Log.debugf(
                    "Collected only %d/%d bytes of whitened entropy (no padding added)",
                    bytesCollected, KernelEntropyWriterService.BYTES_PER_WRITE);
        }

        return result;
    }

    /**
     * Initialize Prometheus metrics.
     * Safe to call even if meterRegistry is null.
     */
    private void initMetrics() {
        if (meterRegistry == null) {
            Log.warn("MeterRegistry not available - metrics disabled");
            return;
        }

        try {
            bytesWrittenCounter =
                    Counter.builder("kernel_entropy_bytes_written_total")
                            .description("Total bytes written to kernel entropy pool")
                            .tag("device", targetDevice)
                            .register(meterRegistry);

            writeFailuresCounter =
                    Counter.builder("kernel_entropy_write_failures_total")
                            .description("Total failed writes to kernel entropy pool")
                            .tag("device", targetDevice)
                            .register(meterRegistry);

            writeRetriesCounter =
                    Counter.builder("kernel_entropy_write_retries_total")
                            .description("Total retry attempts for kernel entropy writes")
                            .tag("device", targetDevice)
                            .register(meterRegistry);

            Log.info("Kernel entropy writer metrics initialized");
        } catch (Exception e) {
            Log.warn("Failed to initialize metrics", e);
        }
    }

    /**
     * Increments a Prometheus counter by the given amount. No-op if the counter
     * is {@code null} (metrics not initialized).
     *
     * @param counter the counter to increment, or {@code null}
     * @param amount  the value to add
     */
    private void incrementCounter(Counter counter, long amount) {
        if (counter != null) {
            counter.increment(amount);
        }
    }

    /**
     * Get total bytes written since service start.
     * Exposed for health checks.
     *
     * @return Total bytes written
     */
    public long getTotalBytesWritten() {
        return totalBytesWritten.get();
    }

    /**
     * Check if writer is operational.
     * Exposed for health checks.
     *
     * @return true if writer is initialized and enabled
     */
    public boolean isOperational() {
        return initialized && writerEnabled;
    }

    /** Last successful write timestamp, or null if nothing has been written yet. */
    public Instant getLastSuccessfulInjectionTimestamp() {
        return lastSuccessfulInjectionTimestamp;
    }
}
