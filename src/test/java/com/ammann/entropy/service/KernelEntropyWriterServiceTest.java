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
    }
}
