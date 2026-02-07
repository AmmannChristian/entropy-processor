/* (C)2026 */
package com.ammann.entropy.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ammann.entropy.dto.NISTSuiteResultDTO;
import com.ammann.entropy.dto.TimeWindowDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class NistValidationServiceHourlyTest {

    @Test
    void runHourlyValidationDoesNotIncrementFailuresOnPass() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TestableNistValidationService service =
                new TestableNistValidationService(registry, this::passingResult, null);

        service.runHourlyNISTValidation();

        assertThat(registry.get("nist_validation_failures_total").counter().count()).isZero();
    }

    @Test
    void runHourlyValidationDoesNotIncrementFailuresOnFailResult() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TestableNistValidationService service =
                new TestableNistValidationService(registry, this::failingResult, null);

        service.runHourlyNISTValidation();

        assertThat(registry.get("nist_validation_failures_total").counter().count()).isZero();
    }

    @Test
    void runHourlyValidationIncrementsFailureCounterOnException() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RuntimeException boom = new RuntimeException("boom");
        TestableNistValidationService service =
                new TestableNistValidationService(registry, null, boom);

        service.runHourlyNISTValidation();

        assertThat(registry.get("nist_validation_failures_total").counter().count()).isEqualTo(1.0);
    }

    private NISTSuiteResultDTO passingResult() {
        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofHours(1));
        return new NISTSuiteResultDTO(
                List.of(),
                1,
                1,
                0,
                1.0,
                true,
                Instant.now(),
                1024L,
                new TimeWindowDTO(start, end, 1L));
    }

    private NISTSuiteResultDTO failingResult() {
        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofHours(1));
        return new NISTSuiteResultDTO(
                List.of(),
                2,
                1,
                1,
                0.5,
                false,
                Instant.now(),
                1024L,
                new TimeWindowDTO(start, end, 1L));
    }

    private static final class TestableNistValidationService extends NistValidationService {
        private final Supplier<NISTSuiteResultDTO> supplier;
        private final RuntimeException failure;

        TestableNistValidationService(
                SimpleMeterRegistry registry,
                Supplier<NISTSuiteResultDTO> supplier,
                RuntimeException failure) {
            super(null, registry, null);
            this.supplier = supplier;
            this.failure = failure;
        }

        @Override
        public NISTSuiteResultDTO validateTimeWindow(Instant start, Instant end) {
            if (failure != null) {
                throw failure;
            }
            return supplier.get();
        }
    }
}
