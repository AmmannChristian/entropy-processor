package com.ammann.entropy.model;

import com.ammann.entropy.dto.NISTTestResultDTO;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "nist_test_results", indexes = {
        @Index(name = "idx_test_suite_run", columnList = "test_suite_run_id"),
        @Index(name = "idx_executed_at", columnList = "executed_at"),
        @Index(name = "idx_passed", columnList = "passed")
})
public class NistTestResult extends PanacheEntity {

    /**
     * Groups multiple tests into a single suite execution.
     * All 15 NIST tests executed together share the same UUID.
     */
    @Column(name = "test_suite_run_id", nullable = false)
    @NotNull
    public UUID testSuiteRunId;

    /**
     * Name of the NIST test (e.g., "Frequency (Monobit) Test", "Runs Test").
     */
    @Column(name = "test_name", nullable = false, length = 100)
    @NotNull
    public String testName;

    /**
     * Optional batch identifier that the test was derived from.
     */
    @Column(name = "batch_id", length = 64)
    public String batchId;

    /**
     * Whether the test passed (p-value >= 0.01).
     */
    @Column(name = "passed", nullable = false)
    @NotNull
    public Boolean passed;

    /**
     * Statistical p-value from the test (0.0 - 1.0).
     * Higher values indicate more random data.
     * Threshold: p-value >= 0.01 for passing.
     */
    @Column(name = "p_value")
    public Double pValue;

    /**
     * Size of the data sample tested (in bits).
     */
    @Column(name = "data_sample_size")
    public Long dataSampleSize;

    /**
     * Number of bits effectively used by the test (post-whitening).
     */
    @Column(name = "bits_tested")
    public Long bitsTested;

    /**
     * Start of the time window from which test data was extracted.
     */
    @Column(name = "window_start", nullable = false)
    @NotNull
    public Instant windowStart;

    /**
     * End of the time window from which test data was extracted.
     */
    @Column(name = "window_end", nullable = false)
    @NotNull
    public Instant windowEnd;

    /**
     * Timestamp when this test was executed.
     */
    @Column(name = "executed_at", nullable = false)
    @NotNull
    public Instant executedAt = Instant.now();

    /**
     * Additional test-specific details in JSON format.
     * Examples: test parameters, intermediate statistics, failure reasons.
     */
    @Column(name = "details", columnDefinition = "jsonb")
    public String details;

    // Constructors

    public NistTestResult() {
    }

    public NistTestResult(UUID testSuiteRunId, String testName, boolean passed,
                          Double pValue, Instant windowStart, Instant windowEnd) {
        this.testSuiteRunId = testSuiteRunId;
        this.testName = testName;
        this.passed = passed;
        this.pValue = pValue;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.executedAt = Instant.now();
    }

    // Panache Queries

    /**
     * Finds all test results for a specific suite run.
     *
     * @param runId UUID of the test suite run
     * @return List of all tests in that run (typically 15 tests)
     */
    public static List<NistTestResult> findByTestSuiteRun(UUID runId) {
        return find("testSuiteRunId", runId).list();
    }

    /**
     * Counts failures in the last 24 hours across all tests.
     *
     * @return Number of failed tests
     */
    public static Long countFailures24h() {
        return count("executedAt > ?1 AND passed = false",
                Instant.now().minus(Duration.ofDays(1)));
    }

    /**
     * Calculates overall pass rate since a given timestamp.
     *
     * @param since Start of calculation window
     * @return Pass rate (0.0 - 1.0)
     */
    public static Double getPassRate(Instant since) {
        Long total = count("executedAt > ?1", since);
        if (total == 0) {
            return 0.0;
        }
        Long passed = count("executedAt > ?1 AND passed = true", since);
        return (double) passed / total;
    }

    /**
     * Finds most recent test suite run.
     *
     * @return UUID of most recent run, or null if no runs exist
     */
    public static UUID findMostRecentSuiteRunId() {
        NistTestResult result = find("1=1 ORDER BY executedAt DESC").firstResult();
        return result != null ? result.testSuiteRunId : null;
    }

    /**
     * Finds all failed tests in the last N days.
     *
     * @param days Number of days to look back
     * @return List of failed test results
     */
    public static List<NistTestResult> findFailures(int days) {
        return find("executedAt > ?1 AND passed = false ORDER BY executedAt DESC",
                Instant.now().minus(Duration.ofDays(days)))
                .list();
    }

    /**
     * Counts how many times a specific test has failed since given timestamp.
     *
     * @param testName Name of the test
     * @param since Start of window
     * @return Failure count
     */
    public static Long countTestFailures(String testName, Instant since) {
        return count("testName = ?1 AND executedAt > ?2 AND passed = false",
                testName, since);
    }

    // Business Methods

    /**
     * Converts entity to DTO for API responses.
     */
    public NISTTestResultDTO toDTO() {
        String status = passed ? "PASS" : "FAIL";
        return new NISTTestResultDTO(
                testName,
                passed,
                pValue,
                status,
                executedAt,
                details
        );
    }

    @Override
    public String toString() {
        return String.format("NISTTestResult{id=%d, test='%s', passed=%b, pValue=%.4f, runId=%s}",
                id, testName, passed, pValue, testSuiteRunId);
    }
}
