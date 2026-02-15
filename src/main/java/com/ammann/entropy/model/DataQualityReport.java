/* (C)2026 */
package com.ammann.entropy.model;

import com.ammann.entropy.dto.ClockDriftInfoDTO;
import com.ammann.entropy.dto.DataQualityReportDTO;
import com.ammann.entropy.dto.DecayRateInfoDTO;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * JPA entity representing a periodic data quality assessment report.
 *
 * <p>Each report covers a time window of entropy events and aggregates metrics
 * for packet loss (missing sequences), clock drift between the Raspberry Pi and
 * the server, decay rate plausibility, and network delay stability into a
 * composite quality score in the range [0.0, 1.0].
 *
 * <p>Persisted to the {@code data_quality_reports} table. Provides Panache
 * finder methods for common query patterns such as retrieving the most recent
 * report or filtering by quality score threshold.
 */
@Entity
@Table(
        name = "data_quality_reports",
        indexes = {
            @Index(name = "idx_report_timestamp", columnList = "report_timestamp"),
            @Index(name = "idx_quality_score", columnList = "overall_quality_score"),
            @Index(name = "idx_window_start", columnList = "window_start")
        })
public class DataQualityReport extends PanacheEntity {

    /**
     * Timestamp when this report was generated.
     */
    @Column(name = "report_timestamp", nullable = false)
    @NotNull
    public Instant reportTimestamp = Instant.now();

    /**
     * Start of the analysis time window.
     */
    @Column(name = "window_start", nullable = false)
    @NotNull
    public Instant windowStart;

    /**
     * End of the analysis time window.
     */
    @Column(name = "window_end", nullable = false)
    @NotNull
    public Instant windowEnd;

    /**
     * Total number of events analyzed in this window.
     */
    @Column(name = "total_events", nullable = false)
    @NotNull
    public Long totalEvents;

    /**
     * Number of missing sequences detected (packet loss indicator).
     */
    @Column(name = "missing_sequence_count")
    public Integer missingSequenceCount;

    /**
     * Clock drift rate in microseconds per hour.
     * Positive values: edge ingestion clock appears fast vs. cloud reception
     * Negative values: edge ingestion clock appears slow vs. cloud reception
     * Threshold for concern: absolute drift greater than 10 us/h
     */
    @Column(name = "clock_drift_us_per_hour")
    public Double clockDriftUsPerHour;

    /**
     * Average delay between edge gateway ingestion and cloud server reception, in milliseconds.
     * Calculated as: (server_received_us - rpi_timestamp_us) / 1000
     */
    @Column(name = "average_network_delay_ms")
    public Double averageNetworkDelayMs;

    /**
     * Average decay interval in milliseconds.
     * Used for entropy source validation.
     */
    @Column(name = "average_decay_interval_ms")
    public Double averageDecayIntervalMs;

    /**
     * Whether the observed decay rate is realistic for entropy.
     * Expected: 0.5ms - 5.0ms for 1 microcurie source
     */
    @Column(name = "decay_rate_realistic")
    public Boolean decayRateRealistic;

    /**
     * Overall quality score (0.0 - 1.0).
     * Composite score based on:
     * - Packet loss rate (1.0 - loss_ratio)
     * - Clock drift severity
     * - Decay rate plausibility
     * - Network delay stability
     */
    @Column(name = "overall_quality_score")
    public Double overallQualityScore;

    /**
     * Array of recommendations for improving data quality.
     * Examples: ["Check NTP sync", "Inspect MQTT connection stability"]
     */
    @Column(name = "recommendations", columnDefinition = "text array")
    public String[] recommendations;

    public DataQualityReport() {}

    /**
     * Constructs a report for the given analysis window and event count.
     *
     * @param windowStart start of the analysis window
     * @param windowEnd   end of the analysis window
     * @param totalEvents total number of events analysed
     */
    public DataQualityReport(Instant windowStart, Instant windowEnd, Long totalEvents) {
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.totalEvents = totalEvents;
    }

    /**
     * Finds the most recent quality report.
     *
     * @return the most recent {@code DataQualityReport}, or {@code null} if none exists
     */
    public static DataQualityReport findMostRecent() {
        return find("ORDER BY reportTimestamp DESC").firstResult();
    }

    /**
     * Finds all reports within a time range.
     *
     * @param start Start of range
     * @param end End of range
     * @return List of reports
     */
    public static List<DataQualityReport> findInRange(Instant start, Instant end) {
        return find(
                        "reportTimestamp >= ?1 AND reportTimestamp <= ?2 ORDER BY reportTimestamp"
                                + " DESC",
                        start,
                        end)
                .list();
    }

    /**
     * Finds reports with quality score below threshold.
     *
     * @param minScore Minimum acceptable quality score
     * @param since Start of search window
     * @return List of low-quality reports
     */
    public static List<DataQualityReport> findLowQuality(double minScore, Instant since) {
        return find(
                        "overallQualityScore < ?1 AND reportTimestamp > ?2 ORDER BY"
                                + " overallQualityScore ASC",
                        minScore,
                        since)
                .list();
    }

    /**
     * Calculates average quality score over last N days.
     *
     * @param days Number of days
     * @return Average quality score or 0.0 if no data
     */
    public static Double getAverageQualityScore(int days) {
        Instant since = Instant.now().minus(Duration.ofDays(days));
        List<DataQualityReport> reports = find("reportTimestamp > ?1", since).list();

        if (reports.isEmpty()) {
            return 0.0;
        }

        return reports.stream()
                .filter(r -> r.overallQualityScore != null)
                .mapToDouble(r -> r.overallQualityScore)
                .average()
                .orElse(0.0);
    }

    /**
     * Finds reports with significant clock drift.
     *
     * @param driftThresholdUsPerHour Drift threshold (e.g., 10.0)
     * @param since Start of search window
     * @return List of reports with excessive drift
     */
    public static List<DataQualityReport> findClockDriftIssues(
            double driftThresholdUsPerHour, Instant since) {
        return find(
                        "ABS(clockDriftUsPerHour) > ?1 AND reportTimestamp > ?2 ORDER BY"
                                + " reportTimestamp DESC",
                        driftThresholdUsPerHour,
                        since)
                .list();
    }

    /**
     * Converts this entity to a {@link DataQualityReportDTO} for API responses.
     *
     * <p>Sequence gap details and gap counts are not persisted in the entity and
     * are therefore returned as empty or zero in the DTO.
     *
     * @return a DTO representation of this report
     */
    public DataQualityReportDTO toDTO() {
        ClockDriftInfoDTO clockDrift =
                clockDriftUsPerHour != null ? ClockDriftInfoDTO.create(clockDriftUsPerHour) : null;

        DecayRateInfoDTO decayRate =
                averageDecayIntervalMs != null
                        ? new DecayRateInfoDTO(
                                averageDecayIntervalMs, decayRateRealistic, null, null)
                        : null;

        return new DataQualityReportDTO(
                totalEvents,
                List.of(), // Gaps not stored in DB entity (computed on demand)
                0, // gapCount not stored
                missingSequenceCount != null ? missingSequenceCount.longValue() : 0L,
                List.of(), // deprecated field, always empty
                clockDrift,
                decayRate,
                averageNetworkDelayMs,
                overallQualityScore,
                reportTimestamp);
    }

    /**
     * Calculates composite quality score from individual metrics.
     * Called before persisting the entity.
     */
    public void calculateQualityScore() {
        double score = 1.0;

        // Packet loss penalty (0.0 - 1.0)
        if (totalEvents > 0 && missingSequenceCount != null) {
            double lossRatio = (double) missingSequenceCount / totalEvents;
            score *= (1.0 - lossRatio);
        }

        // Clock drift penalty
        if (clockDriftUsPerHour != null) {
            double absDrift = Math.abs(clockDriftUsPerHour);
            if (absDrift > 10.0) {
                score *= 0.95; // 5% penalty for significant drift
            }
            if (absDrift > 50.0) {
                score *= 0.85; // Additional 15% penalty for severe drift
            }
        }

        // Decay rate plausibility check
        if (Boolean.FALSE.equals(decayRateRealistic)) {
            score *= 0.90; // 10% penalty for unrealistic decay rate
        }

        // Network delay stability (penalize high variance)
        if (averageNetworkDelayMs != null && averageNetworkDelayMs > 100.0) {
            score *= 0.95; // 5% penalty for high network delay
        }

        this.overallQualityScore = Math.clamp(score, 0.0, 1.0);
    }

    @Override
    public String toString() {
        return String.format(
                "DataQualityReport{id=%d, window=%s to %s, events=%d, score=%.3f, missing=%d}",
                id, windowStart, windowEnd, totalEvents, overallQualityScore, missingSequenceCount);
    }
}
