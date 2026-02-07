package com.ammann.entropy.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = EntropyData.TABLE_NAME, indexes = {
        @Index(name = "idx_hw_timestamp_ns", columnList = "hw_timestamp_ns"),
        @Index(name = "idx_sequence", columnList = "sequence"),
        @Index(name = "idx_server_received", columnList = "server_received"),
        @Index(name = "idx_batch_id", columnList = "batch_id")
})
public class EntropyData extends PanacheEntity
{
    public static final String TABLE_NAME = "entropy_data";

    /**
     * Batch identifier from the edge gateway.
     */
    @Column(name = "batch_id", length = 64)
    public String batchId;

    /**
     * Human-readable ISO 8601 timestamp from Raspberry Pi.
     * Used for debugging and compatibility with external systems.
     */
    @Column(nullable = false, length = 64)
    @NotNull
    public String timestamp;

    /**
     * Hardware timestamp in nanoseconds since Unix epoch.
     * This is the PRIMARY field for entropy interval calculations.
     * Derived from TDC picosecond timestamp (ps / 1000 = ns).
     */
    @Column(name = "hw_timestamp_ns", nullable = false)
    @NotNull
    @Min(value = 0, message = "Hardware timestamp must be positive")
    public Long hwTimestampNs;

    /**
     * Sequence number from Raspberry Pi for packet loss detection.
     * Monotonically increasing per device session.
     */
    @Column(name = "sequence", nullable = false)
    @NotNull
    @Min(value = 0, message = "Sequence number must be non-negative")
    public Long sequenceNumber;

    /**
     * Hardware channel the event originated from.
     */
    @Column
    public Integer channel;

    /**
     * Raspberry Pi timestamp in microseconds.
     */
    @Column(name = "rpi_timestamp_us")
    public Long rpiTimestampUs;

    /**
     * Raw TDC timestamp in picoseconds.
     */
    @Column(name = "tdc_timestamp_ps")
    public Long tdcTimestampPs;

    /**
     * Whitened entropy bytes derived from the event timestamps.
     */
    @Column(name = "whitened_entropy")
    public byte[] whitenedEntropy;

    /**
     * Server reception timestamp,used as TimescaleDB partitioning key.
     * Automatically set when packet is received.
     */
    @Column(name = "server_received", nullable = false)
    @NotNull
    public Instant serverReceived;

    /**
     * Network delay in milliseconds between hardware event and server reception.
     * Calculated as: server_received - hw_timestamp_ns
     * Used for network performance analysis.
     */
    @Column(name = "network_delay_ms")
    public Long networkDelayMs;

    /**
     * Database insertion timestamp.
     * Used for audit trails and debugging.
     */
    @Column(name = "created_at", nullable = false)
    @NotNull
    public Instant createdAt;

    /**
     * Source IP address of the Raspberry Pi.
     * Useful for multi-device setups or security monitoring.
     */
    @Column(name = "source_address", length = 45) // IPv6 compatible
    public String sourceAddress = null;

    /**
     * Data quality score (0.0 - 1.0) for filtering low-quality events.
     * Can be used by entropy algorithms to weight events.
     */
    @Column(name = "quality_score")
    public Double qualityScore = 1.0;

    // Constructors
    public EntropyData()
    {
        this.serverReceived = Instant.now();
        this.createdAt = Instant.now();
    }

    public EntropyData(String timestamp, Long hwTimestampNs, Long sequenceNumber)
    {
        this();
        this.timestamp = timestamp;
        this.hwTimestampNs = hwTimestampNs;
        this.sequenceNumber = sequenceNumber;
    }

    // Business methods for entropy calculations

    /**
     * Calculates intervals between consecutive events for a given time window.
     * This is the core data for all entropy algorithms.
     *
     * @param start Start of time window
     * @param end   End of time window
     * @return List of intervals in nanoseconds
     */
    public static List<Long> calculateIntervals(Instant start, Instant end)
    {
        @SuppressWarnings("unchecked")
        List<Number> rows = getEntityManager()
                .createNativeQuery("""
                            SELECT hw_timestamp_ns - lag(hw_timestamp_ns) OVER (ORDER BY hw_timestamp_ns) AS delta_ns
                            FROM entropy_data
                            WHERE server_received >= :start
                              AND server_received <  :end
                        """)
                .setParameter("start", start)
                .setParameter("end", end)
                .getResultList();

        return rows.stream()
                .map(n -> n == null ? null : n.longValue())
                .filter(Objects::nonNull)
                .filter(v -> v > 0)
                .toList();
    }

    public static IntervalStats calculateIntervalStats(Instant start, Instant end) {
        Object[] row = (Object[]) getEntityManager()
                .createNativeQuery("""
            WITH d AS (
              SELECT hw_timestamp_ns - lag(hw_timestamp_ns) OVER (ORDER BY hw_timestamp_ns) AS delta_ns
              FROM entropy_data
              WHERE server_received >= :start
                AND server_received <  :end
            )
            SELECT
              count(delta_ns)              AS cnt,
              avg(delta_ns)::float8        AS mean_ns,
              stddev_pop(delta_ns)::float8 AS stddev_ns,
              min(delta_ns)                AS min_ns,
              max(delta_ns)                AS max_ns,
              percentile_cont(0.5) WITHIN GROUP (ORDER BY delta_ns)::float8 AS median_ns
            FROM d
            WHERE delta_ns > 0
        """)
                .setParameter("start", start)
                .setParameter("end", end)
                .getSingleResult();

        long count = ((Number) row[0]).longValue();
        double mean = row[1] == null ? 0.0 : ((Number) row[1]).doubleValue();
        double std  = row[2] == null ? 0.0 : ((Number) row[2]).doubleValue();
        long min    = row[3] == null ? 0L  : ((Number) row[3]).longValue();
        long max    = row[4] == null ? 0L  : ((Number) row[4]).longValue();
        double median = row[5] == null ? 0.0 : ((Number) row[5]).doubleValue();

        return new IntervalStats(count, mean, std, min, max, median);
    }

    /**
     * Gets events in chronological order for sequential processing.
     * Optimized query using hw_timestamp_ns index.
     */
    public static List<EntropyData> findInTimeWindow(Instant start, Instant end)
    {
        return find("serverReceived BETWEEN ?1 AND ?2 ORDER BY hwTimestampNs", start, end).list();
    }

    /**
     * Calculates the interval to the previous event in nanoseconds.
     * Returns null if this is the first event.
     */
    public Long getIntervalToPrevious()
    {
        EntropyData previous = find("hwTimestampNs < ?1 ORDER BY hwTimestampNs DESC", hwTimestampNs)
                .firstResult();

        return previous != null ? hwTimestampNs - previous.hwTimestampNs : null;
    }

    /**
     * Gets statistical summary for the last N events.
     */
    public static EntropyStatistics getRecentStatistics(int eventCount)
    {
        List<EntropyData> recentEvents = find("ORDER BY hwTimestampNs DESC")
                .range(0, eventCount - 1)
                .list();

        if (recentEvents.size() < 2) {
            return new EntropyStatistics(0, 0, 0, 0);
        }

        List<Long> intervals = recentEvents.stream()
                .sorted((a, b) -> Long.compare(a.hwTimestampNs, b.hwTimestampNs))
                .toList()
                .stream()
                .map(EntropyData::getIntervalToPrevious)
                .filter(Objects::nonNull)
                .toList();

        return EntropyStatistics.fromIntervals(intervals);
    }

    /**
     * Validates data integrity and quality.
     * Used by the UDP server before persistence.
     */
    public boolean isValidForEntropy()
    {
        // Basic field validation
        if (hwTimestampNs == null || hwTimestampNs <= 0) return false;
        if (sequenceNumber == null || sequenceNumber < 0) return false;

        // Temporal validation, not too far in future/past
        long nowNs = System.currentTimeMillis() * 1_000_000L;
        long maxSkewNs = 60_000_000_000L; // 60 seconds

        if (hwTimestampNs > nowNs + maxSkewNs) return false;
        if (hwTimestampNs < nowNs - (24 * 60 * 60 * 1_000_000_000L)) return false;

        // Quality score validation
        return qualityScore == null || (qualityScore >= 0.0 && qualityScore <= 1.0);
    }

    @Override
    public String toString()
    {
        return String.format(
                "EntropyData{id=%d, batch=%s, hwTimestampNs=%d, sequence=%d, networkDelay=%dms, quality=%.2f}",
                id, batchId, hwTimestampNs, sequenceNumber, networkDelayMs, qualityScore
        );
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (!(o instanceof EntropyData that)) return false;
        return Objects.equals(hwTimestampNs, that.hwTimestampNs) &&
                Objects.equals(sequenceNumber, that.sequenceNumber);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(hwTimestampNs, sequenceNumber);
    }

    /**
     * Statistical summary record for entropy analysis.
     */
    public record EntropyStatistics(
            double meanInterval,
            double stdDeviation,
            long minInterval,
            long maxInterval
    )
    {
        public static EntropyStatistics fromIntervals(List<Long> intervals)
        {
            if (intervals.isEmpty()) {
                return new EntropyStatistics(0, 0, 0, 0);
            }

            double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(0);
            long min = intervals.stream().mapToLong(Long::longValue).min().orElse(0);
            long max = intervals.stream().mapToLong(Long::longValue).max().orElse(0);

            double variance = intervals.stream()
                    .mapToDouble(interval -> Math.pow(interval - mean, 2))
                    .average()
                    .orElse(0);
            double stdDev = Math.sqrt(variance);

            return new EntropyStatistics(mean, stdDev, min, max);
        }
    }
}
