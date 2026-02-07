package com.ammann.entropy.service;

import com.ammann.entropy.dto.ClockDriftInfoDTO;
import com.ammann.entropy.dto.SequenceGapDTO;
import com.ammann.entropy.model.EntropyData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link DataQualityService}.
 *
 * <p>Covers sequence gap detection (including large gap OOM resilience),
 * clock drift analysis, decay rate plausibility checks, and composite
 * quality score computation.
 */
class DataQualityServiceTest
{

    private final DataQualityService service = new DataQualityService();

    @Test
    void detectSequenceGapsFindsAllGaps()
    {
        Instant now = Instant.now();
        List<EntropyData> events = List.of(
                event(1, 1_000_000L, now),
                event(2, 2_000_000L, now.plusMillis(10)),
                event(4, 3_000_000L, now.plusMillis(20))
        );

        List<SequenceGapDTO> gaps = service.detectSequenceGaps(events);

        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).startSequence()).isEqualTo(3L);
        assertThat(gaps.get(0).endSequence()).isEqualTo(3L);
        assertThat(gaps.get(0).gapSize()).isEqualTo(1L);
    }

    @Test
    void detectSequenceGapsHandlesMultipleGaps()
    {
        Instant now = Instant.now();
        List<EntropyData> events = List.of(
                event(1, 1_000_000L, now),
                event(5, 2_000_000L, now.plusMillis(10)),   // gap: 2,3,4
                event(10, 3_000_000L, now.plusMillis(20))   // gap: 6,7,8,9
        );

        List<SequenceGapDTO> gaps = service.detectSequenceGaps(events);

        assertThat(gaps).hasSize(2);
        assertThat(gaps.get(0).startSequence()).isEqualTo(2L);
        assertThat(gaps.get(0).endSequence()).isEqualTo(4L);
        assertThat(gaps.get(0).gapSize()).isEqualTo(3L);
        assertThat(gaps.get(1).startSequence()).isEqualTo(6L);
        assertThat(gaps.get(1).endSequence()).isEqualTo(9L);
        assertThat(gaps.get(1).gapSize()).isEqualTo(4L);
    }

    @Test
    void detectSequenceGapsHandlesLargeGapWithoutOOM()
    {
        // This test verifies the fix for the OOM issue
        // A gap of 10 million sequences should NOT cause memory issues
        Instant now = Instant.now();
        List<EntropyData> events = List.of(
                event(1, 1_000_000L, now),
                event(10_000_002, 2_000_000L, now.plusMillis(10)) // 10 million missing sequences
        );

        List<SequenceGapDTO> gaps = service.detectSequenceGaps(events);

        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).startSequence()).isEqualTo(2L);
        assertThat(gaps.get(0).endSequence()).isEqualTo(10_000_001L);
        assertThat(gaps.get(0).gapSize()).isEqualTo(10_000_000L);
    }

    @Test
    void detectSequenceGapsReturnsEmptyForContiguousSequences()
    {
        Instant now = Instant.now();
        List<EntropyData> events = List.of(
                event(1, 1_000_000L, now),
                event(2, 2_000_000L, now.plusMillis(10)),
                event(3, 3_000_000L, now.plusMillis(20))
        );

        List<SequenceGapDTO> gaps = service.detectSequenceGaps(events);

        assertThat(gaps).isEmpty();
    }

    @Test
    void checkClockDriftDetectsTrendOverTime()
    {
        Instant base = Instant.now().minusSeconds(7_200);
        List<EntropyData> events = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            EntropyData e = event(i + 1, 5_000_000L + i * 1000, base.plusSeconds(i * 600L));
            e.networkDelayMs = (i < 10) ? 5L : 25L;
            events.add(e);
        }

        ClockDriftInfoDTO drift = service.checkClockDrift(events);

        assertThat(drift.isSignificant()).isTrue();
        assertThat(drift.driftRateUsPerHour()).isPositive();
    }
    
    @Test
    void realisticDecayRateChecksBounds()
    {
        assertThat(service.isRealisticDecayRate(List.of(4_000_000L, 5_000_000L, 6_000_000L))).isTrue();
        assertThat(service.isRealisticDecayRate(List.of(33_000_000L, 37_000_000L))).isFalse();
    }

    @ParameterizedTest
    @MethodSource("decayRateSamples")
    void realisticDecayRateUsesSpreadAndOutliers(List<Long> intervals, boolean expected)
    {
        assertThat(service.isRealisticDecayRate(intervals)).isEqualTo(expected);
    }

    static java.util.stream.Stream<Arguments> decayRateSamples()
    {
        return java.util.stream.Stream.of(
                Arguments.of(List.of(4_800_000L, 5_200_000L, 5_600_000L, 6_000_000L), true),
                Arguments.of(List.of(35_000_000L, 40_000_000L, 45_000_000L), false),
                Arguments.of(List.of(200_000L, 250_000L, 300_000L), false)
        );
    }

    @Test
    void assessDataQualityReturnsPerfectScoreForHealthyData()
    {
        Instant start = Instant.now().minusSeconds(600);
        List<EntropyData> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long seq = i + 1;
            long hwTs = 1_000_000_000L + (i * 1_000_000L); // 1ms spacing in ns
            EntropyData e = event(seq, hwTs, start.plusSeconds(i * 60L));
            e.networkDelayMs = 10L;
            events.add(e);
        }

        var report = service.assessDataQuality(events);

        assertThat(report.missingSequenceCount()).isZero();
        assertThat(report.sequenceGaps()).isEmpty();
        assertThat(report.gapCount()).isZero();
        assertThat(report.clockDrift().isSignificant()).isFalse();
        assertThat(report.decayRate().isRealistic()).isTrue();
        assertThat(report.qualityScore()).isEqualTo(1.0);
    }

    @Test
    void assessDataQualityPenalizesLossDriftAndDecay()
    {
        Instant start = Instant.now().minusSeconds(7_200);
        List<EntropyData> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long seq = (i >= 2) ? i + 2 : i + 1; // gap at sequence 3
            long hwTs = 1_000_000_000L + (i * 30_000_000L); // 30ms spacing in ns -> above max acceptable ~27ms
            EntropyData e = event(seq, hwTs, start.plusSeconds(i * 720L)); // spread over 2h
            e.networkDelayMs = (i < 5) ? 5L : 120L; // induce positive drift
            events.add(e);
        }

        var report = service.assessDataQuality(events);

        assertThat(report.missingSequenceCount()).isEqualTo(1);
        assertThat(report.sequenceGaps()).hasSize(1);
        assertThat(report.sequenceGaps().get(0).startSequence()).isEqualTo(3L);
        assertThat(report.sequenceGaps().get(0).endSequence()).isEqualTo(3L);
        assertThat(report.gapCount()).isEqualTo(1);
        assertThat(report.missingSequences()).isEmpty(); // deprecated field is always empty
        assertThat(report.clockDrift().isSignificant()).isTrue();
        assertThat(report.decayRate().isRealistic()).isFalse();
        assertThat(report.qualityScore()).isCloseTo(0.69255, within(0.01));
    }

    @Test
    void assessDataQualityHandlesLargeGapWithoutOOM()
    {
        // This test verifies that assessDataQuality works with large gaps
        Instant now = Instant.now();
        List<EntropyData> events = List.of(
                event(1, 1_000_000L, now),
                event(10_000_002, 2_000_000L, now.plusMillis(10))
        );

        var report = service.assessDataQuality(events);

        assertThat(report.missingSequenceCount()).isEqualTo(10_000_000L);
        assertThat(report.sequenceGaps()).hasSize(1);
        assertThat(report.gapCount()).isEqualTo(1);
        assertThat(report.sequenceGaps().get(0).gapSize()).isEqualTo(10_000_000L);
        assertThat(report.missingSequences()).isEmpty(); // deprecated field
    }

    @Test
    void assessDataQualityReturnsNullForEmptyInput()
    {
        assertThat(service.assessDataQuality(List.of())).isNull();
        assertThat(service.assessDataQuality(null)).isNull();
    }

    @Test
    void checkClockDriftReturnsZeroWhenNotEnoughData()
    {
        ClockDriftInfoDTO drift = service.checkClockDrift(List.of(event(1, 10_000_000L, Instant.now())));
        assertThat(drift.driftRateUsPerHour()).isZero();
        assertThat(drift.isSignificant()).isFalse();
    }

    @Test
    void unrealisticDecayRateForNullOrEmptyIntervals()
    {
        assertThat(service.isRealisticDecayRate(List.of())).isFalse();
        assertThat(service.isRealisticDecayRate(null)).isFalse();
    }

    /**
     * Creates a minimal {@link EntropyData} fixture with the given sequence number,
     * hardware timestamp, and server reception time. Derived fields (RPI timestamp,
     * TDC timestamp, network delay) are populated with plausible defaults.
     */
    private EntropyData event(long sequence, long hwTimestampNs, Instant serverReceived)
    {
        EntropyData e = new EntropyData("ts-" + hwTimestampNs, hwTimestampNs, sequence);
        e.serverReceived = serverReceived;
        e.createdAt = serverReceived;
        e.rpiTimestampUs = hwTimestampNs / 1_000; // Convert ns to µs
        e.tdcTimestampPs = hwTimestampNs * 1_000; // Convert ns to ps
        e.networkDelayMs = 5L;
        e.sourceAddress = "sensor-1";
        return e;
    }
}
