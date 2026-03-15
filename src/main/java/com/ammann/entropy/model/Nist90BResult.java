/* (C)2026 */
package com.ammann.entropy.model;

import com.ammann.entropy.dto.NIST90BResultDTO;
import com.ammann.entropy.dto.TimeWindowDTO;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persistent entity storing aggregate results of a NIST SP 800-90B entropy assessment.
 *
 * <p>Each row represents a single assessment run against a bitstream extracted from
 * entropy events within a time window. Individual estimator results (14 total: 10 Non-IID + 4 IID)
 * are stored in {@link Nist90BEstimatorResult} with full metadata (passed, details, description).
 *
 * <p>This entity stores only the aggregate results: overall min-entropy and pass status.
 */
@Entity
@Table(
        name = Nist90BResult.TABLE_NAME,
        indexes = {
            @Index(name = "idx_90b_executed_at", columnList = "executed_at"),
            @Index(name = "idx_90b_passed", columnList = "passed"),
            @Index(name = "idx_90b_assessment_run", columnList = "assessment_run_id"),
            @Index(
                    name = "idx_90b_assessment_run_chunk",
                    columnList = "assessment_run_id, chunk_index")
        })
public class Nist90BResult extends PanacheEntity {

    public static final String TABLE_NAME = "nist_90b_results";

    /** Batch identifier linking this result to the source entropy data. */
    @Column(name = "batch_id", length = 64)
    public String batchId;

    /** Assessment run identifier grouping all chunks from a single validation job. */
    @Column(name = "assessment_run_id")
    public java.util.UUID assessmentRunId;

    /** Overall min-entropy estimate in bits per symbol (most conservative bound). */
    @Column(name = "min_entropy")
    public Double minEntropy;

    /** Whether the overall assessment passed the minimum entropy threshold. */
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

    /** Index of this chunk within the assessment run (0-based, null for single-chunk runs). */
    @Column(name = "chunk_index")
    public Integer chunkIndex;

    /** Total number of chunks in the assessment run (null for single-chunk runs). */
    @Column(name = "chunk_count")
    public Integer chunkCount;

    /**
     * Discriminates the canonical run-level result from per-chunk forensic rows.
     *
     * <p>When {@code true}, this row is the single authoritative result for the entire
     * assessment run: its minEntropy is the minimum across all chunks, and its passed
     * flag is the conjunction. When {@code false}, this is an individual per-chunk row
     * retained for forensic analysis. The absence of a summary row for a given
     * {@code assessmentRunId} indicates an incomplete or failed run.
     */
    @Column(name = "is_run_summary", nullable = false)
    public boolean isRunSummary = false;

    /** 1-based index of this sample within the window assessment. Null for legacy rows. */
    @Column(name = "sample_index")
    public Integer sampleIndex;

    /** Total number of samples in this window assessment. */
    @Column(name = "sample_count")
    public Integer sampleCount;

    /** Start byte offset (inclusive) within the assembled bitstream for this sample. */
    @Column(name = "sample_byte_offset_start")
    public Long sampleByteOffsetStart;

    /** End byte offset (exclusive) within the assembled bitstream for this sample. */
    @Column(name = "sample_byte_offset_end")
    public Long sampleByteOffsetEnd;

    /** hwTimestampNs of the first entropy event contributing to this sample. */
    @Column(name = "sample_first_event_timestamp")
    public Instant sampleFirstEventTimestamp;

    /** hwTimestampNs of the last entropy event contributing to this sample. */
    @Column(name = "sample_last_event_timestamp")
    public Instant sampleLastEventTimestamp;

    /**
     * Assessment scope discriminator.
     * NIST_SINGLE_SAMPLE: individual NIST-valid SP 800-90B assessment.
     * PRODUCT_WINDOW_SUMMARY: conservative aggregation across N independent NIST assessments.
     */
    @Column(name = "assessment_scope", length = 30)
    public String assessmentScope = "NIST_SINGLE_SAMPLE";

    /**
     * Whether the actual sample size meets the NIST SP 800-90B §3.1.2 minimum
     * recommendation of 1,000,000 bytes (for 8-bit symbols). Null for legacy rows.
     */
    @Column(name = "sample_size_meets_nist_minimum")
    public Boolean sampleSizeMeetsNistMinimum;

    public Nist90BResult() {}

    public Nist90BResult(
            String batchId,
            Double minEntropy,
            boolean passed,
            String assessmentDetails,
            Long bitsTested,
            Instant windowStart,
            Instant windowEnd) {
        this.batchId = batchId;
        this.minEntropy = minEntropy;
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
     * @return DTO representation with aggregate assessment results
     */
    public NIST90BResultDTO toDTO() {
        TimeWindowDTO window =
                new TimeWindowDTO(
                        windowStart, windowEnd, Duration.between(windowStart, windowEnd).toHours());
        return new NIST90BResultDTO(
                minEntropy != null ? minEntropy : 0.0,
                passed,
                assessmentDetails,
                executedAt,
                bitsTested != null ? bitsTested : 0L,
                window,
                assessmentRunId,
                isRunSummary,
                sampleIndex,
                sampleCount,
                sampleByteOffsetStart,
                sampleByteOffsetEnd,
                sampleFirstEventTimestamp,
                sampleLastEventTimestamp,
                assessmentScope,
                sampleSizeMeetsNistMinimum);
    }
}
