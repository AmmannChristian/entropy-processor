/* (C)2026 */
package com.ammann.entropy.model;

import com.ammann.entropy.enumeration.JobStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;

/**
 * Tracks a single entropy source comparison run across BASELINE, HARDWARE, and MIXED sources.
 */
@Entity
@Table(name = "entropy_comparison_run")
public class EntropyComparisonRun extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "run_timestamp", nullable = false)
    public Instant runTimestamp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public JobStatus status;

    @Column(name = "sp80022_sample_size_bytes", nullable = false)
    public Integer sp80022SampleSizeBytes;

    @Column(name = "sp80090b_sample_size_bytes", nullable = false)
    public Integer sp80090bSampleSizeBytes;

    @Column(name = "metrics_sample_size_bytes", nullable = false)
    public Integer metricsSampleSizeBytes;

    @Column(name = "mixed_valid")
    public Boolean mixedValid;

    @Column(name = "mixed_injection_timestamp")
    public Instant mixedInjectionTimestamp;

    @Column(name = "created_at")
    public Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    public Instant completedAt;

    public static List<EntropyComparisonRun> findRecent(int limit) {
        return find("ORDER BY createdAt DESC").page(0, limit).list();
    }

    public static long countByStatus(JobStatus status) {
        return count("status", status);
    }
}
