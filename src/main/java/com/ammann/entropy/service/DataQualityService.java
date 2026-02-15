/* (C)2026 */
package com.ammann.entropy.service;

import com.ammann.entropy.dto.ClockDriftInfoDTO;
import com.ammann.entropy.dto.DataQualityReportDTO;
import com.ammann.entropy.dto.DecayRateInfoDTO;
import com.ammann.entropy.dto.SequenceGapDTO;
import com.ammann.entropy.model.EntropyData;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Service for assessing the quality of entropy event streams.
 *
 * <p>Evaluates multiple quality dimensions including sequence gap detection (packet loss),
 * drift analysis between edge gateway ingestion time and server reception time, decay rate plausibility
 * for the entropy source, and network delay stability. Produces a composite quality score
 * in the range [0.0, 1.0].
 *
 * <p>The expected detector count rate is configurable via {@code entropy.source.expected-rate-hz}.
 * The expected interval and acceptable range are derived from this rate to ensure consistency
 * with {@link com.ammann.entropy.dto.EventRateResponseDTO}.
 */
@ApplicationScoped
public class DataQualityService {

    private static final Logger LOG = Logger.getLogger(DataQualityService.class);

    static final double DEFAULT_EXPECTED_RATE_HZ = 184.0;

    /**
     * Expected detector count rate in Hz. Configurable via application.properties.
     * The expected interval and acceptable range are derived from this value.
     */
    @ConfigProperty(name = "entropy.source.expected-rate-hz", defaultValue = "184.0")
    double expectedRateHz = DEFAULT_EXPECTED_RATE_HZ;

    /**
     * Returns the expected inter-event interval in milliseconds, derived from
     * the configured detector count rate.
     *
     * @return expected interval in milliseconds
     */
    double getExpectedIntervalMs() {
        double rate = expectedRateHz > 0 ? expectedRateHz : DEFAULT_EXPECTED_RATE_HZ;
        return 1000.0 / rate;
    }

    /**
     * Returns the minimum acceptable inter-event interval (10 percent of expected).
     *
     * @return lower bound in milliseconds
     */
    double getMinAcceptableIntervalMs() {
        return getExpectedIntervalMs() * 0.1;
    }

    /**
     * Returns the maximum acceptable inter-event interval (five times the expected).
     *
     * @return upper bound in milliseconds
     */
    double getMaxAcceptableIntervalMs() {
        return getExpectedIntervalMs() * 5.0;
    }

    /**
     * Performs comprehensive data quality assessment on a list of events.
     *
     * @param events List of EntropyData events (should be ordered by sequence)
     * @return DataQualityReportDTO with quality metrics
     */
    public DataQualityReportDTO assessDataQuality(List<EntropyData> events) {
        if (events == null || events.isEmpty()) {
            LOG.warn("Cannot assess quality of empty event list");
            return null;
        }

        // Detect sequence gaps as ranges (memory-efficient)
        List<SequenceGapDTO> sequenceGaps = detectSequenceGaps(events);
        long totalMissing = sequenceGaps.stream().mapToLong(SequenceGapDTO::gapSize).sum();

        // Calculate average network delay
        double avgNetworkDelay =
                events.stream()
                        .filter(e -> e.networkDelayMs != null)
                        .mapToLong(e -> e.networkDelayMs)
                        .average()
                        .orElse(0.0);

        // Calculate intervals for decay rate check
        List<Long> intervals = calculateIntervals(events);
        double avgIntervalNs =
                intervals.isEmpty()
                        ? 0.0
                        : intervals.stream().mapToLong(Long::longValue).average().orElse(0.0);
        double avgIntervalMs = avgIntervalNs / 1_000_000.0;

        // Check decay rate plausibility (derived from configured expected rate)
        DecayRateInfoDTO decayRate =
                DecayRateInfoDTO.create(
                        avgIntervalMs,
                        getExpectedIntervalMs(),
                        getMinAcceptableIntervalMs(),
                        getMaxAcceptableIntervalMs());

        // Calculate clock drift
        ClockDriftInfoDTO clockDrift = checkClockDrift(events);

        // Calculate overall quality score
        double qualityScore =
                calculateQualityScore(events.size(), totalMissing, clockDrift, decayRate);

        LOG.infof(
                "Quality assessment: %d events, %d missing sequences in %d gaps, score=%.3f",
                events.size(), totalMissing, sequenceGaps.size(), qualityScore);

        return new DataQualityReportDTO(
                (long) events.size(),
                sequenceGaps,
                sequenceGaps.size(),
                totalMissing,
                List.of(), // deprecated field, always empty
                clockDrift,
                decayRate,
                avgNetworkDelay,
                qualityScore,
                Instant.now());
    }

    /**
     * Checks if decay rate is realistic for entropy source.
     *
     * @param intervals List of intervals in nanoseconds
     * @return true if decay rate is realistic
     */
    public boolean isRealisticDecayRate(List<Long> intervals) {
        if (intervals == null || intervals.isEmpty()) {
            return false;
        }

        double avgIntervalNs = intervals.stream().mapToLong(Long::longValue).average().orElse(0.0);
        double avgIntervalMs = avgIntervalNs / 1_000_000.0;

        boolean realistic =
                avgIntervalMs >= getMinAcceptableIntervalMs()
                        && avgIntervalMs <= getMaxAcceptableIntervalMs();

        if (!realistic) {
            LOG.warnf(
                    "Unrealistic decay rate: avg interval = %.2f ms (expected: %.2f ms)",
                    avgIntervalMs, getExpectedIntervalMs());
        }

        return realistic;
    }

    /**
     * Detects sequence gaps as ranges (memory-efficient).
     * Instead of enumerating every missing sequence (which can cause OOM for large gaps),
     * this method returns a list of gap ranges with start, end, and count.
     *
     * @param events List of EntropyData events (must be ordered by sequence)
     * @return List of SequenceGapDTO representing gap ranges
     */
    public List<SequenceGapDTO> detectSequenceGaps(List<EntropyData> events) {
        if (events == null || events.size() < 2) {
            return List.of();
        }

        List<SequenceGapDTO> gaps = new ArrayList<>();

        for (int i = 1; i < events.size(); i++) {
            long prevSeq = events.get(i - 1).sequenceNumber;
            long currentSeq = events.get(i).sequenceNumber;
            long gapSize = currentSeq - prevSeq - 1;

            if (gapSize > 0) {
                gaps.add(SequenceGapDTO.of(prevSeq + 1, currentSeq - 1));
            }
        }

        if (!gaps.isEmpty()) {
            long totalMissing = gaps.stream().mapToLong(SequenceGapDTO::gapSize).sum();
            LOG.warnf(
                    "Detected %d sequence gaps containing %d missing sequences (packet loss)",
                    gaps.size(), totalMissing);
        }

        return gaps;
    }

    /**
     * @deprecated Use {@link #detectSequenceGaps(List)} instead to avoid OOM on large gaps.
     * This method is kept for backward compatibility but will throw if gaps are too large.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    public List<Long> detectMissingSequences(List<EntropyData> events) {
        if (events == null || events.size() < 2) {
            return List.of();
        }

        List<Long> missing = new ArrayList<>();
        long maxEnumerable = 100_000; // Safety limit

        for (int i = 1; i < events.size(); i++) {
            long prevSeq = events.get(i - 1).sequenceNumber;
            long currentSeq = events.get(i).sequenceNumber;
            long gapSize = currentSeq - prevSeq - 1;

            if (gapSize > maxEnumerable) {
                throw new IllegalStateException(
                        "Gap too large to enumerate ("
                                + gapSize
                                + " sequences). Use detectSequenceGaps() instead for"
                                + " memory-efficient gap detection.");
            }

            if (gapSize > 0) {
                for (long seq = prevSeq + 1; seq < currentSeq; seq++) {
                    missing.add(seq);
                }
            }
        }

        return missing;
    }

    /**
     * Analyzes drift between edge gateway ingestion timestamps and server reception time.
     *
     * @param events List of EntropyData events
     * @return ClockDriftInfo with drift rate and recommendations
     */
    public ClockDriftInfoDTO checkClockDrift(List<EntropyData> events) {
        if (events == null || events.size() < 10) {
            // Not enough data for drift analysis
            return ClockDriftInfoDTO.create(0.0);
        }

        // Calculate drift trend using linear regression
        // Compare network delay at start vs. end of time window
        List<EntropyData> sortedEvents =
                events.stream()
                        .filter(e -> e.networkDelayMs != null && e.serverReceived != null)
                        .sorted((a, b) -> a.serverReceived.compareTo(b.serverReceived))
                        .toList();

        if (sortedEvents.size() < 10) {
            return ClockDriftInfoDTO.create(0.0);
        }

        // Take first 10% and last 10% of events
        int sampleSize = sortedEvents.size() / 10;
        if (sampleSize < 5) sampleSize = 5;

        List<EntropyData> startSample =
                sortedEvents.subList(0, Math.min(sampleSize, sortedEvents.size()));
        List<EntropyData> endSample =
                sortedEvents.subList(
                        Math.max(0, sortedEvents.size() - sampleSize), sortedEvents.size());

        double avgDelayStart =
                startSample.stream().mapToLong(e -> e.networkDelayMs).average().orElse(0.0);

        double avgDelayEnd =
                endSample.stream().mapToLong(e -> e.networkDelayMs).average().orElse(0.0);

        // Calculate time span in hours
        long timeSpanMs =
                sortedEvents.get(sortedEvents.size() - 1).serverReceived.toEpochMilli()
                        - sortedEvents.get(0).serverReceived.toEpochMilli();
        double timeSpanHours = timeSpanMs / (1000.0 * 60.0 * 60.0);

        if (timeSpanHours < 0.001) {
            // Time span too short for meaningful drift calculation
            return ClockDriftInfoDTO.create(0.0);
        }

        // Drift rate in microseconds per hour
        double delayDriftMs = avgDelayEnd - avgDelayStart;
        double driftRateUsPerHour = (delayDriftMs * 1000.0) / timeSpanHours;

        LOG.debugf(
                "Clock drift analysis: %.2f µs/h over %.2f hours (delay: %.2f -> %.2f ms)",
                driftRateUsPerHour, timeSpanHours, avgDelayStart, avgDelayEnd);

        return ClockDriftInfoDTO.create(driftRateUsPerHour);
    }

    /**
     * Computes consecutive inter-event intervals from hardware timestamps.
     * Events are sorted by hardware timestamp; only positive intervals are retained.
     *
     * @param events list of entropy events
     * @return list of positive intervals in nanoseconds
     */
    private List<Long> calculateIntervals(List<EntropyData> events) {
        if (events.size() < 2) {
            return List.of();
        }

        List<Long> intervals = new ArrayList<>(events.size() - 1);
        List<EntropyData> sorted =
                events.stream()
                        .sorted((a, b) -> Long.compare(a.hwTimestampNs, b.hwTimestampNs))
                        .toList();

        for (int i = 1; i < sorted.size(); i++) {
            long interval = sorted.get(i).hwTimestampNs - sorted.get(i - 1).hwTimestampNs;
            if (interval > 0) {
                intervals.add(interval);
            }
        }

        return intervals;
    }

    /**
     * Computes a composite quality score in [0.0, 1.0] by applying multiplicative
     * penalties for packet loss, clock drift, and unrealistic decay rate.
     *
     * @param totalEvents  total number of events in the window
     * @param missingCount number of missing sequence numbers
     * @param clockDrift   clock drift analysis result (may be {@code null})
     * @param decayRate    decay rate plausibility result (may be {@code null})
     * @return clamped quality score
     */
    private double calculateQualityScore(
            int totalEvents,
            long missingCount,
            ClockDriftInfoDTO clockDrift,
            DecayRateInfoDTO decayRate) {

        double score = 1.0;

        // Packet loss penalty (0.0 - 1.0)
        if (totalEvents > 0 && missingCount > 0) {
            double lossRatio = (double) missingCount / totalEvents;
            score *= (1.0 - lossRatio);
        }

        // Clock drift penalty
        if (clockDrift != null && Boolean.TRUE.equals(clockDrift.isSignificant())) {
            score *= 0.95; // 5% penalty for significant drift
            if (Math.abs(clockDrift.driftRateUsPerHour()) > 50.0) {
                score *= 0.90; // Additional 10% penalty for severe drift
            }
        }

        // Decay rate plausibility penalty
        if (decayRate != null && Boolean.FALSE.equals(decayRate.isRealistic())) {
            score *= 0.90; // 10% penalty for unrealistic decay rate
        }

        return Math.clamp(score, 0.0, 1.0);
    }
}
