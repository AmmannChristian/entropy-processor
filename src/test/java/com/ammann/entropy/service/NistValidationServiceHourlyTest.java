/* (C)2026 */
package com.ammann.entropy.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ammann.entropy.dto.NIST90BResultDTO;
import com.ammann.entropy.dto.NISTSuiteResultDTO;
import com.ammann.entropy.dto.TimeWindowDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class NistValidationServiceHourlyTest {

    @Test
    void runHourlyValidationDoesNotIncrementFailuresOnPass() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TestableNistValidationService service =
                new TestableNistValidationService(
                        registry, this::passingResult, null, this::passing90bResult, null);

        service.runHourlyNISTValidation();

        assertThat(registry.get("nist_validation_failures_total").counter().count()).isZero();
        assertThat(service.sp80022CallCount).isEqualTo(1);
        assertThat(service.sp80090bCallCount).isZero();
    }

    @Test
    void runHourlyValidationDoesNotIncrementFailuresOnFailResult() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TestableNistValidationService service =
                new TestableNistValidationService(
                        registry, this::failingResult, null, this::passing90bResult, null);

        service.runHourlyNISTValidation();

        assertThat(registry.get("nist_validation_failures_total").counter().count()).isZero();
        assertThat(service.sp80022CallCount).isEqualTo(1);
        assertThat(service.sp80090bCallCount).isZero();
    }

    @Test
    void runHourlyValidationIncrementsFailureCounterOnException() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RuntimeException boom = new RuntimeException("boom");
        TestableNistValidationService service =
                new TestableNistValidationService(
                        registry, null, boom, this::passing90bResult, null);

        service.runHourlyNISTValidation();

        assertThat(registry.get("nist_validation_failures_total").counter().count()).isEqualTo(1.0);
        assertThat(service.sp80022CallCount).isEqualTo(1);
        assertThat(service.sp80090bCallCount).isZero();
    }

    @Test
    void runWeeklyValidationTriggersOnly90b() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TestableNistValidationService service =
                new TestableNistValidationService(
                        registry, this::passingResult, null, this::passing90bResult, null);

        service.runWeeklyNIST90BValidation();

        assertThat(registry.get("nist_validation_failures_total").counter().count()).isZero();
        assertThat(service.sp80022CallCount).isZero();
        assertThat(service.sp80090bCallCount).isEqualTo(1);
    }

    @Test
    void runWeeklyValidationIncrementsFailureCounterOnException() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RuntimeException boom = new RuntimeException("boom");
        TestableNistValidationService service =
                new TestableNistValidationService(registry, this::passingResult, null, null, boom);

        service.runWeeklyNIST90BValidation();

        assertThat(registry.get("nist_validation_failures_total").counter().count()).isEqualTo(1.0);
        assertThat(service.sp80022CallCount).isZero();
        assertThat(service.sp80090bCallCount).isEqualTo(1);
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

    private NIST90BResultDTO passing90bResult() {
        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofDays(7));
        return new NIST90BResultDTO(
                7.5,
                true,
                "ok",
                Instant.now(),
                1024L,
                new TimeWindowDTO(start, end, Duration.between(start, end).toHours()),
                UUID.randomUUID());
    }

    private static final class TestableNistValidationService extends NistValidationService {
        private final Supplier<NISTSuiteResultDTO> sp80022Supplier;
        private final RuntimeException sp80022Failure;
        private final Supplier<NIST90BResultDTO> sp80090bSupplier;
        private final RuntimeException sp80090bFailure;
        private int sp80022CallCount;
        private int sp80090bCallCount;

        TestableNistValidationService(
                SimpleMeterRegistry registry,
                Supplier<NISTSuiteResultDTO> sp80022Supplier,
                RuntimeException sp80022Failure,
                Supplier<NIST90BResultDTO> sp80090bSupplier,
                RuntimeException sp80090bFailure) {
            super(null, registry, null);
            this.sp80022Supplier = sp80022Supplier;
            this.sp80022Failure = sp80022Failure;
            this.sp80090bSupplier = sp80090bSupplier;
            this.sp80090bFailure = sp80090bFailure;
        }

        @Override
        public UUID startAsyncSp80022Validation(
                Instant start, Instant end, String bearerToken, String username) {
            sp80022CallCount++;
            if (sp80022Failure != null) {
                throw sp80022Failure;
            }
            // Return a dummy UUID since we're not actually creating a job
            return UUID.randomUUID();
        }

        @Override
        public UUID startAsyncSp80090bValidation(
                Instant start, Instant end, String bearerToken, String username) {
            sp80090bCallCount++;
            if (sp80090bFailure != null) {
                throw sp80090bFailure;
            }
            // Return a dummy UUID since we're not actually creating a job
            return UUID.randomUUID();
        }
    }
}
