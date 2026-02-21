/* (C)2026 */
package com.ammann.entropy.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ammann.entropy.model.EntropyData;
import com.ammann.entropy.support.TestDataFactory;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class KernelEntropyWriterServiceTest {

    @Inject KernelEntropyWriterService serviceProxy;

    KernelEntropyWriterService service;

    @BeforeEach
    void setUp() {
        service = ClientProxy.unwrap(serviceProxy);
    }

    @AfterEach
    void tearDown() throws Exception {
        service.cleanup();
        service.writerEnabled = false;
        resetInternalCounters();
    }

    @Test
    @TestTransaction
    void feedKernelEntropyWritesToDevice() throws IOException {
        Path device = Files.createTempFile("kernel-entropy", ".bin");
        configureService(device);
        EntropyData.deleteAll();

        byte[] entropy = new byte[128];
        Arrays.fill(entropy, (byte) 0x5A);

        EntropyData event =
                TestDataFactory.createEntropyEvent(1, 1_000L, Instant.now().minusSeconds(1));
        event.whitenedEntropy = entropy;
        event.persist();

        service.feedKernelEntropy();

        assertThat(Files.size(device)).isEqualTo(entropy.length);
        assertThat(service.getTotalBytesWritten()).isEqualTo(entropy.length);
    }

    @Test
    @TestTransaction
    void feedKernelEntropySkipsWhenNoEvents() throws IOException {
        Path device = Files.createTempFile("kernel-entropy-empty", ".bin");
        configureService(device);
        EntropyData.deleteAll();

        service.feedKernelEntropy();

        assertThat(Files.size(device)).isZero();
        assertThat(service.getTotalBytesWritten()).isZero();
    }

    @Test
    void isOperational_returnsFalseWhenNotInitialized() {
        service.writerEnabled = true;
        // initialized is false by default (from resetInternalCounters in tearDown)
        assertThat(service.isOperational()).isFalse();
    }

    @Test
    @TestTransaction
    void isOperational_returnsTrueWhenEnabledAndInitialized() throws IOException {
        Path device = Files.createTempFile("kernel-operational", ".bin");
        configureService(device);
        assertThat(service.isOperational()).isTrue();
    }

    @Test
    void isOperational_returnsFalseWhenDisabled() {
        service.writerEnabled = false;
        assertThat(service.isOperational()).isFalse();
    }

    @Test
    @TestTransaction
    void getLastSuccessfulInjectionTimestamp_nullBeforeAnyWrite() throws IOException {
        Path device = Files.createTempFile("kernel-ts-null", ".bin");
        configureService(device);

        assertThat(service.getLastSuccessfulInjectionTimestamp()).isNull();
    }

    @Test
    @TestTransaction
    void getLastSuccessfulInjectionTimestamp_setAfterSuccessfulWrite() throws IOException {
        Path device = Files.createTempFile("kernel-ts-set", ".bin");
        configureService(device);
        EntropyData.deleteAll();

        byte[] entropy = new byte[64];
        Arrays.fill(entropy, (byte) 0xAB);
        EntropyData event =
                TestDataFactory.createEntropyEvent(1, 1_000L, Instant.now().minusSeconds(1));
        event.whitenedEntropy = entropy;
        event.persist();

        Instant before = Instant.now();
        service.feedKernelEntropy();
        Instant after = Instant.now();

        Instant ts = service.getLastSuccessfulInjectionTimestamp();
        assertThat(ts).isNotNull();
        assertThat(ts).isBetween(before, after);
    }

    @Test
    @TestTransaction
    void feedKernelEntropy_nullWhitenedEntropy_skipsEvent() throws IOException {
        Path device = Files.createTempFile("kernel-null-whitened", ".bin");
        configureService(device);
        EntropyData.deleteAll();

        EntropyData event =
                TestDataFactory.createEntropyEvent(1, 1_000L, Instant.now().minusSeconds(1));
        event.whitenedEntropy = null; // Null whitened entropy should be skipped.
        event.persist();

        service.feedKernelEntropy();

        assertThat(Files.size(device)).isZero();
        assertThat(service.getTotalBytesWritten()).isZero();
    }

    @Test
    @TestTransaction
    void feedKernelEntropy_zeroLengthWhitenedEntropy_skipsEvent() throws IOException {
        Path device = Files.createTempFile("kernel-zero-whitened", ".bin");
        configureService(device);
        EntropyData.deleteAll();

        EntropyData event =
                TestDataFactory.createEntropyEvent(1, 1_000L, Instant.now().minusSeconds(1));
        event.whitenedEntropy = new byte[0]; // Empty whitened entropy should be skipped.
        event.persist();

        service.feedKernelEntropy();

        assertThat(Files.size(device)).isZero();
        assertThat(service.getTotalBytesWritten()).isZero();
    }

    @Test
    @TestTransaction
    void feedKernelEntropy_multipleEventsMoreThan512Bytes_capsAtBufferSize() throws IOException {
        Path device = Files.createTempFile("kernel-multi", ".bin");
        configureService(device);
        EntropyData.deleteAll();

        // Provide more bytes than BYTES_PER_WRITE (512) across multiple events
        for (int i = 0; i < 5; i++) {
            byte[] entropy = new byte[200];
            Arrays.fill(entropy, (byte) (i + 1));
            EntropyData event =
                    TestDataFactory.createEntropyEvent(
                            i + 1L, 1_000L + i, Instant.now().minusSeconds(10 - i));
            event.whitenedEntropy = entropy;
            event.persist();
        }

        service.feedKernelEntropy();

        // Should be capped at 512 bytes (BYTES_PER_WRITE)
        assertThat(Files.size(device)).isEqualTo(512L);
        assertThat(service.getTotalBytesWritten()).isEqualTo(512L);
    }

    @Test
    @TestTransaction
    void feedKernelEntropy_disabledButInitialized_skips() throws IOException {
        Path device = Files.createTempFile("kernel-disabled", ".bin");
        configureService(device);
        service.writerEnabled = false; // disabled after init

        EntropyData event =
                TestDataFactory.createEntropyEvent(1, 1_000L, Instant.now().minusSeconds(1));
        event.whitenedEntropy = new byte[] {0x01, 0x02};
        event.persist();

        service.feedKernelEntropy();

        assertThat(Files.size(device)).isZero();
    }

    @Test
    @TestTransaction
    void writeWithRetry_failsAllAttempts_returnsFalse() throws Exception {
        // Write to /dev/null won't work; we need a closed stream to simulate failure.
        // Point device at a directory (unwritable) to force IOException on write.
        Path device = Files.createTempFile("kernel-fail", ".bin");
        configureService(device);

        // Close the stream to force write failure
        service.cleanup();

        // Invoke writeWithRetry via reflection on the closed stream
        var method =
                KernelEntropyWriterService.class.getDeclaredMethod("writeWithRetry", byte[].class);
        method.setAccessible(true);

        // Because the stream is closed, all writes fail and the method should return false.
        boolean result = (boolean) method.invoke(service, (Object) new byte[] {0x01});
        assertThat(result).isFalse();
    }

    private void configureService(Path device) throws IOException {
        service.cleanup();
        service.writerEnabled = true;
        service.targetDevice = device.toString();
        service.init();
    }

    private void resetInternalCounters() throws Exception {
        var bytesField = KernelEntropyWriterService.class.getDeclaredField("totalBytesWritten");
        bytesField.setAccessible(true);
        ((AtomicLong) bytesField.get(service)).set(0);

        var initField = KernelEntropyWriterService.class.getDeclaredField("initialized");
        initField.setAccessible(true);
        initField.setBoolean(service, false);

        var tsField =
                KernelEntropyWriterService.class.getDeclaredField(
                        "lastSuccessfulInjectionTimestamp");
        tsField.setAccessible(true);
        tsField.set(service, null);
    }
}
