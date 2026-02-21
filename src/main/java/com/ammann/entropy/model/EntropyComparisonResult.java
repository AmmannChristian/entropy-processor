/* (C)2026 */
package com.ammann.entropy.model;

import com.ammann.entropy.enumeration.EntropySourceType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Stores NIST test and entropy metric results for one source type within a comparison run.
 */
@Entity
@Table(name = "entropy_comparison_result")
public class EntropyComparisonResult extends PanacheEntityBase {

    @Id
    @GeneratedValue(generator = "entropy_comparison_result_SEQ")
    @SequenceGenerator(
            name = "entropy_comparison_result_SEQ",
            sequenceName = "entropy_comparison_result_SEQ",
            allocationSize = 50)
    public Long id;

    @Column(name = "comparison_run_id")
    public Long comparisonRunId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    public EntropySourceType sourceType;

    @Column(name = "bytes_collected")
    public Integer bytesCollected;

    @Column(name = "sp80022_bytes_used")
    public Integer sp80022BytesUsed;

    @Column(name = "sp80090b_bytes_used")
    public Integer sp80090bBytesUsed;

    @Column(name = "metrics_bytes_used")
    public Integer metricsBytesUsed;

    @Column(name = "nist_22_pass_rate", precision = 5, scale = 2)
    public BigDecimal nist22PassRate;

    @Column(name = "nist_22_p_value_mean", precision = 10, scale = 8)
    public BigDecimal nist22PValueMean;

    @Column(name = "nist_22_p_value_min", precision = 10, scale = 8)
    public BigDecimal nist22PValueMin;

    @Column(name = "nist_22_executed_tests")
    public Integer nist22ExecutedTests;

    @Column(name = "nist_22_skipped_tests")
    public Integer nist22SkippedTests;

    @Column(name = "nist_22_status", length = 30)
    public String nist22Status;

    @Column(name = "min_entropy_estimate", precision = 10, scale = 8)
    public BigDecimal minEntropyEstimate;

    @Column(name = "nist_90b_status", length = 30)
    public String nist90bStatus;

    @Column(name = "shannon_entropy", precision = 10, scale = 8)
    public BigDecimal shannonEntropy;

    @Column(name = "renyi_entropy", precision = 10, scale = 8)
    public BigDecimal renyiEntropy;

    @Column(name = "sample_entropy", precision = 10, scale = 8)
    public BigDecimal sampleEntropy;

    @Column(name = "created_at")
    public Instant createdAt = Instant.now();

    public static List<EntropyComparisonResult> findByRunId(Long runId) {
        return find("comparisonRunId = ?1 ORDER BY sourceType", runId).list();
    }
}
