package com.ammann.entropy.model;

import com.ammann.entropy.dto.NIST90BResultDTO;
import com.ammann.entropy.dto.TimeWindowDTO;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.Instant;

/**
 * Persistent entity storing the result of a NIST SP 800-90B entropy assessment.
 *
 * <p>Each row represents a single assessment run against a bitstream extracted from
 * entropy events within a time window. Multiple entropy estimators (Shannon, collision,
 * Markov, compression) are recorded alongside the overall min-entropy and pass status.
 */
@Entity
@Table(name = Nist90BResult.TABLE_NAME, indexes = {
        @Index(name = "idx_90b_executed_at", columnList = "executed_at"),
        @Index(name = "idx_90b_passed", columnList = "passed")
})
public class Nist90BResult extends PanacheEntity {

    public static final String TABLE_NAME = "nist_90b_results";

    /** Batch identifier linking this result to the source entropy data. */
    @Column(name = "batch_id", length = 64)
    public String batchId;

    /** Overall min-entropy estimate in bits per symbol (most conservative bound). */
    @Column(name = "min_entropy")
    public Double minEntropy;

    /** Shannon entropy estimate in bits per symbol. */
    @Column(name = "shannon_entropy")
    public Double shannonEntropy;

    /** Collision entropy estimate in bits per symbol. */
    @Column(name = "collision_entropy")
    public Double collisionEntropy;

    /** Markov entropy estimate in bits per symbol. */
    @Column(name = "markov_entropy")
    public Double markovEntropy;

    /** Compression-based entropy estimate in bits per symbol. */
    @Column(name = "compression_entropy")
    public Double compressionEntropy;

    /** Whether the assessment passed the minimum entropy threshold. */
    @Column(name = "passed", nullable = false)
    public boolean passed;

    /** Detailed assessment output stored as a JSONB document. */
    @JdbcTypeCode(SqlTypes.JSON)
    @ColumnTransformer(write = "CAST(? AS jsonb)")
    @Column(name = "assessment_details", columnDefinition = "jsonb")
    public String assessmentDetails;

    /** Number of bits in the assessed bitstream. */
    @Column(name = "bits_tested")
    public Long bitsTested;

    /** Start of the time window from which the assessed data was drawn. */
    @Column(name = "window_start", nullable = false)
    public Instant windowStart;

    /** End of the time window from which the assessed data was drawn. */
    @Column(name = "window_end", nullable = false)
    public Instant windowEnd;

    /** Timestamp when this assessment was executed. */
    @Column(name = "executed_at", nullable = false)
    public Instant executedAt = Instant.now();

    public Nist90BResult() {
    }

    public Nist90BResult(String batchId,
                         Double minEntropy,
                         Double shannonEntropy,
                         Double collisionEntropy,
                         Double markovEntropy,
                         Double compressionEntropy,
                         boolean passed,
                         String assessmentDetails,
                         Long bitsTested,
                         Instant windowStart,
                         Instant windowEnd) {
        this.batchId = batchId;
        this.minEntropy = minEntropy;
        this.shannonEntropy = shannonEntropy;
        this.collisionEntropy = collisionEntropy;
        this.markovEntropy = markovEntropy;
        this.compressionEntropy = compressionEntropy;
        this.passed = passed;
        this.assessmentDetails = assessmentDetails;
        this.bitsTested = bitsTested;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.executedAt = Instant.now();
    }

    /**
     * Converts this entity to its corresponding API response DTO.
     *
     * @return DTO representation with null-safe default values for missing estimates
     */
    public NIST90BResultDTO toDTO() {
        TimeWindowDTO window = new TimeWindowDTO(windowStart, windowEnd, Duration.between(windowStart, windowEnd).toHours());
        return new NIST90BResultDTO(
                minEntropy != null ? minEntropy : 0.0,
                shannonEntropy != null ? shannonEntropy : 0.0,
                collisionEntropy != null ? collisionEntropy : 0.0,
                markovEntropy != null ? markovEntropy : 0.0,
                compressionEntropy != null ? compressionEntropy : 0.0,
                passed,
                assessmentDetails,
                executedAt,
                bitsTested != null ? bitsTested : 0L,
                window
        );
    }
}
