/* (C)2026 */
package com.ammann.entropy.model;

import com.ammann.entropy.enumeration.TestType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persistent entity storing individual NIST SP 800-90B estimator test results.
 * @see Nist90BResult Parent assessment result entity
 */
@Entity
@Table(
        name = "nist_90b_estimator_results",
        indexes = {
            @Index(name = "idx_estimator_assessment", columnList = "assessment_run_id"),
            @Index(name = "idx_estimator_type", columnList = "test_type")
        },
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_estimator_per_run",
                    columnNames = {"assessment_run_id", "test_type", "estimator_name"})
        })
public class Nist90BEstimatorResult extends PanacheEntityBase {

    /** Primary key (auto-generated). */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** Assessment run identifier linking this estimator to a parent assessment. */
    @Column(name = "assessment_run_id", nullable = false)
    public UUID assessmentRunId;

    /** Test type (IID or NON_IID). */
    @Enumerated(EnumType.STRING)
    @Column(name = "test_type", nullable = false, length = 10)
    public TestType testType;

    /** Estimator name (e.g., "Collision Test", "Chi-Square Test"). */
    @Column(name = "estimator_name", nullable = false, length = 100)
    public String estimatorName;

    /**
     * Entropy estimate in bits per sample.
     *
     * <p>NULL for non-entropy tests (e.g., Chi-Square, LRS). 0.0 represents true zero entropy
     * (degenerate source). Upstream -1.0 is mapped to NULL by service layer to distinguish from
     * true zero.
     */
    @Column(name = "entropy_estimate")
    public Double entropyEstimate;

    /** Whether this individual estimator test passed. */
    @Column(nullable = false)
    public boolean passed;

    /**
     * Estimator-specific metadata as JSONB object.
     *
     * <p>Examples: chi_square value, degrees of freedom, cutoff thresholds. NULL for estimators
     * without additional details.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details_json", columnDefinition = "jsonb")
    public Map<String, Double> details;

    /** Human-readable description of what this estimator tests. */
    @Column(columnDefinition = "TEXT")
    public String description;

    /** No-arg constructor required by JPA. */
    public Nist90BEstimatorResult() {}

    /**
     * Constructs a new estimator result.
     *
     * @param assessmentRunId Assessment run identifier
     * @param testType Test type (IID or NON_IID)
     * @param estimatorName Estimator name
     * @param entropyEstimate Entropy estimate (NULL for non-entropy tests)
     * @param passed Whether test passed
     * @param details Estimator-specific metadata (NULL if none)
     * @param description Human-readable description
     */
    public Nist90BEstimatorResult(
            UUID assessmentRunId,
            TestType testType,
            String estimatorName,
            Double entropyEstimate,
            boolean passed,
            Map<String, Double> details,
            String description) {
        this.assessmentRunId = assessmentRunId;
        this.testType = testType;
        this.estimatorName = estimatorName;
        this.entropyEstimate = entropyEstimate;
        this.passed = passed;
        this.details = details;
        this.description = description;
    }
}
