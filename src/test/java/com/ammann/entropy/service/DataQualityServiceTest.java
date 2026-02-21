/* (C)2026 */
package com.ammann.entropy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.ammann.entropy.dto.ClockDriftInfoDTO;
import com.ammann.entropy.dto.SequenceGapDTO;
import com.ammann.entropy.model.EntropyData;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for {@link DataQualityService}.
 *
 * <p>Covers sequence gap detection (including large gap OOM resilience),
 * clock drift analysis, decay rate plausibility checks, and composite
 * quality score computation.
 */
class DataQualityServiceTest {

    private final DataQualityService service = new DataQualityService();

    @Test
    void detectSequenceGapsFindsAllGaps() {
        Instant now = Instant.now();
        List<EntropyData> events =
                List.of(
                        event(1, 1_000_000L, now),
                        event(2, 2_000_000L, now.plusMillis(10)),
                        event(4, 3_000_000L, now.plusMillis(20)));

        List<SequenceGapDTO> gaps = service.detectSequenceGaps(events);

        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).startSequence()).isEqualTo(3L);
        assertThat(gaps.get(0).endSequence()).isEqualTo(3L);
        assertThat(gaps.get(0).gapSize()).isEqualTo(1L);
    }

    @Test
    void detectSequenceGapsHandlesMultipleGaps() {
        Instant now = Instant.now();
        List<EntropyData> events =
                List.of(
                        event(1, 1_000_000L, now),
                        event(5, 2_000_000L, now.plusMillis(10)), // gap: 2,3,4
                        event(10, 3_000_000L, now.plusMillis(20)) // gap: 6,7,8,9
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
    void detectSequenceGapsHandlesLargeGapWithoutOOM() {
        // This test verifies the fix for the OOM issue
        // A gap of 10 million sequences should NOT cause memory issues
        Instant now = Instant.now();
        List<EntropyData> events =
                List.of(
                        event(1, 1_000_000L, now),
                        event(
                                10_000_002,
                                2_000_000L,
                                now.plusMillis(10)) // 10 million missing sequences
                        );

        List<SequenceGapDTO> gaps = service.detectSequenceGaps(events);

        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).startSequence()).isEqualTo(2L);
        assertThat(gaps.get(0).endSequence()).isEqualTo(10_000_001L);
        assertThat(gaps.get(0).gapSize()).isEqualTo(10_000_000L);
    }

    @Test
    void detectSequenceGapsReturnsEmptyForContiguousSequences() {
        Instant now = Instant.now();
        List<EntropyData> events =
                List.of(
                        event(1, 1_000_000L, now),
                        event(2, 2_000_000L, now.plusMillis(10)),
                        event(3, 3_000_000L, now.plusMillis(20)));

        List<SequenceGapDTO> gaps = service.detectSequenceGaps(events);

        assertThat(gaps).isEmpty();
    }

    @Test
    void detectSequenceGapsSortsWithinSourceBeforeGapDetection() {
        Instant now = Instant.now();
        EntropyData seq12 = event(12, 3_000_000L, now.plusMillis(20));
        seq12.sourceAddress = "sensor-1";

        EntropyData seq10 = event(10, 1_000_000L, now);
        seq10.sourceAddress = "sensor-1";

        List<SequenceGapDTO> gaps = service.detectSequenceGaps(List.of(seq12, seq10));

        assertThat(gaps).hasSize(1);
        assertThat(gaps.getFirst().startSequence()).isEqualTo(11L);
        assertThat(gaps.getFirst().endSequence()).isEqualTo(11L);
        assertThat(gaps.getFirst().gapSize()).isEqualTo(1L);
    }

    @Test
    void detectSequenceGapsDoesNotCreateCrossSourceGaps() {
        Instant now = Instant.now();
        EntropyData a1 = event(1, 1_000_000L, now);
        a1.sourceAddress = "sensor-a";
        EntropyData a2 = event(2, 2_000_000L, now.plusMillis(10));
        a2.sourceAddress = "sensor-a";

        EntropyData b100 = event(100, 3_000_000L, now.plusMillis(20));
        b100.sourceAddress = "sensor-b";
        EntropyData b101 = event(101, 4_000_000L, now.plusMillis(30));
        b101.sourceAddress = "sensor-b";

        List<SequenceGapDTO> gaps = service.detectSequenceGaps(List.of(a1, b100, a2, b101));

        assertThat(gaps).isEmpty();
    }

    @Test
    void checkClockDriftDetectsTrendOverTime() {
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
    void realisticDecayRateChecksBounds() {
        assertThat(service.isRealisticDecayRate(List.of(4_000_000L, 5_000_000L, 6_000_000L)))
                .isTrue();
        assertThat(service.isRealisticDecayRate(List.of(33_000_000L, 37_000_000L))).isFalse();
    }

    @ParameterizedTest
    @MethodSource("decayRateSamples")
    void realisticDecayRateUsesSpreadAndOutliers(List<Long> intervals, boolean expected) {
        assertThat(service.isRealisticDecayRate(intervals)).isEqualTo(expected);
    }

    static java.util.stream.Stream<Arguments> decayRateSamples() {
        return java.util.stream.Stream.of(
                Arguments.of(List.of(4_800_000L, 5_200_000L, 5_600_000L, 6_000_000L), true),
                Arguments.of(List.of(35_000_000L, 40_000_000L, 45_000_000L), false),
                Arguments.of(List.of(200_000L, 250_000L, 300_000L), false));
    }

    @Test
    void assessDataQualityReturnsPerfectScoreForHealthyData() {
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
    void assessDataQualityPenalizesLossDriftAndDecay() {
        Instant start = Instant.now().minusSeconds(7_200);
        List<EntropyData> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long seq = (i >= 2) ? i + 2 : i + 1; // gap at sequence 3
            long hwTs =
                    1_000_000_000L
                            + (i * 30_000_000L); // 30 ms spacing in ns, above the approximately
            // 27 ms limit.
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
    void assessDataQualityHandlesLargeGapWithoutOOM() {
        // This test verifies that assessDataQuality works with large gaps
        Instant now = Instant.now();
        List<EntropyData> events =
                List.of(
                        event(1, 1_000_000L, now),
                        event(10_000_002, 2_000_000L, now.plusMillis(10)));

        var report = service.assessDataQuality(events);

        assertThat(report.missingSequenceCount()).isEqualTo(10_000_000L);
        assertThat(report.sequenceGaps()).hasSize(1);
        assertThat(report.gapCount()).isEqualTo(1);
        assertThat(report.sequenceGaps().get(0).gapSize()).isEqualTo(10_000_000L);
        assertThat(report.missingSequences()).isEmpty(); // deprecated field
    }

    @Test
    void assessDataQualityReturnsNullForEmptyInput() {
        assertThat(service.assessDataQuality(List.of())).isNull();
        assertThat(service.assessDataQuality(null)).isNull();
    }

    @Test
    void checkClockDriftReturnsZeroWhenNotEnoughData() {
        ClockDriftInfoDTO drift =
                service.checkClockDrift(List.of(event(1, 10_000_000L, Instant.now())));
        assertThat(drift.driftRateUsPerHour()).isZero();
        assertThat(drift.isSignificant()).isFalse();
    }

    @Test
    void unrealisticDecayRateForNullOrEmptyIntervals() {
        assertThat(service.isRealisticDecayRate(List.of())).isFalse();
        assertThat(service.isRealisticDecayRate(null)).isFalse();
    }

    // detectSequenceGaps: null input, size below two, null element, and null sourceAddress.
    @Test
    void detectSequenceGaps_nullInput_returnsEmpty() {
        assertThat(service.detectSequenceGaps(null)).isEmpty();
    }

    @Test
    void detectSequenceGaps_singleEvent_returnsEmpty() {
        Instant now = Instant.now();
        assertThat(service.detectSequenceGaps(List.of(event(1, 1_000_000L, now)))).isEmpty();
    }

    @Test
    void detectSequenceGaps_nullElementInList_isFiltered() {
        Instant now = Instant.now();
        List<EntropyData> events = new ArrayList<>();
        events.add(null);
        events.add(event(1, 1_000_000L, now));
        events.add(event(3, 3_000_000L, now.plusMillis(10)));

        // null element is filtered; gap at seq=2 detected from the two valid events
        List<SequenceGapDTO> gaps = service.detectSequenceGaps(events);
        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).startSequence()).isEqualTo(2L);
    }

    @Test
    void detectSequenceGaps_eventWithNullSequenceNumber_isFiltered() {
        Instant now = Instant.now();
        EntropyData noSeq = event(99, 9_000_000L, now);
        noSeq.sequenceNumber = null;

        List<EntropyData> events = new ArrayList<>();
        events.add(event(1, 1_000_000L, now));
        events.add(noSeq);
        events.add(event(2, 2_000_000L, now.plusMillis(5)));

        // Null sequence events are filtered, leaving contiguous sequence values 1 and 2.
        assertThat(service.detectSequenceGaps(events)).isEmpty();
    }

    @Test
    void detectSequenceGaps_nullSourceAddress_groupsAsUnknownSource() {
        Instant now = Instant.now();
        EntropyData e1 = event(1, 1_000_000L, now);
        e1.sourceAddress = null; // normalizeSourceId maps null to "unknown-source".
        EntropyData e2 = event(3, 3_000_000L, now.plusMillis(10));
        e2.sourceAddress = null;

        List<SequenceGapDTO> gaps = service.detectSequenceGaps(List.of(e1, e2));
        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).startSequence()).isEqualTo(2L);
    }

    // detectMissingSequences (deprecated method): branch coverage.
    @Test
    void detectMissingSequences_nullOrSingleEvent_returnsEmpty() {
        Instant now = Instant.now();
        assertThat(service.detectMissingSequences(null)).isEmpty();
        assertThat(service.detectMissingSequences(List.of(event(1, 1_000_000L, now)))).isEmpty();
    }

    @Test
    void detectMissingSequences_normalGap_returnsMissingSequences() {
        Instant now = Instant.now();
        List<EntropyData> events =
                List.of(event(1, 1_000_000L, now), event(5, 5_000_000L, now.plusMillis(10)));

        List<Long> missing = service.detectMissingSequences(events);
        assertThat(missing).containsExactlyInAnyOrder(2L, 3L, 4L);
    }

    @Test
    void detectMissingSequences_contiguousEvents_returnsEmpty() {
        Instant now = Instant.now();
        List<EntropyData> events =
                List.of(
                        event(1, 1_000_000L, now),
                        event(2, 2_000_000L, now.plusMillis(5)),
                        event(3, 3_000_000L, now.plusMillis(10)));

        assertThat(service.detectMissingSequences(events)).isEmpty();
    }

    @Test
    void detectMissingSequences_gapTooLarge_throwsIllegalStateException() {
        Instant now = Instant.now();
        List<EntropyData> events =
                List.of(
                        event(1, 1_000_000L, now),
                        event(200_003, 2_000_000L, now.plusMillis(10)) // gap > 100_000
                        );

        assertThatThrownBy(() -> service.detectMissingSequences(events))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Gap too large");
    }

    // checkClockDrift: sortedEvents below ten after filtering and too-short time span.
    @Test
    void checkClockDrift_filteredEventsBelowThreshold_returnsZero() {
        // 15 events exist, but only 5 have networkDelayMs set, below the threshold.
        Instant base = Instant.now().minusSeconds(3600);
        List<EntropyData> events = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            EntropyData e = event(i + 1, 1_000_000L + i * 100_000, base.plusSeconds(i * 60L));
            e.networkDelayMs = (i < 5) ? 10L : null; // Only the first five events have delay.
            events.add(e);
        }

        ClockDriftInfoDTO drift = service.checkClockDrift(events);
        assertThat(drift.driftRateUsPerHour()).isZero();
        assertThat(drift.isSignificant()).isFalse();
    }

    @Test
    void checkClockDrift_allEventsAtSameInstant_returnsZero() {
        // Time span is zero, so timeSpanHours is below 0.001 and drift is reported as zero.
        Instant now = Instant.now();
        List<EntropyData> events = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            EntropyData e = event(i + 1, 1_000_000L + i * 100_000, now); // same Instant
            e.networkDelayMs = 5L;
            events.add(e);
        }

        ClockDriftInfoDTO drift = service.checkClockDrift(events);
        assertThat(drift.driftRateUsPerHour()).isZero();
    }

    // getExpectedIntervalMs: invalid rate falls back to the default.
    @Test
    void getExpectedIntervalMs_invalidRate_usesDefault() throws Exception {
        Field field = DataQualityService.class.getDeclaredField("expectedRateHz");
        field.setAccessible(true);
        field.setDouble(service, 0.0); // Invalid value uses DEFAULT_EXPECTED_RATE_HZ.

        double interval = service.getExpectedIntervalMs();

        // DEFAULT_EXPECTED_RATE_HZ is 184.0, so the expected interval is approximately 5.43 ms.
        assertThat(interval).isCloseTo(1000.0 / 184.0, within(0.01));
    }

    // assessDataQuality: calculateIntervals edge cases.
    @Test
    void assessDataQuality_singleEvent_usesEmptyIntervals() {
        // Single-event input yields no intervals and therefore zero-valued interval aggregates.
        Instant now = Instant.now();
        EntropyData e = event(1, 1_000_000L, now);
        e.networkDelayMs = 5L;

        var report = service.assessDataQuality(List.of(e));
        // With a single event, assessDataQuality returns null (size<1 check)? Let's check
        // events.isEmpty() is false for one event, so processing continues.
        assertThat(report).isNotNull();
        assertThat(report.sequenceGaps())
                .isEmpty(); // With one event, detectSequenceGaps returns an empty list.
    }

    @Test
    void assessDataQuality_eventsWithSameHwTimestamp_nonPositiveIntervalSkipped() {
        // Two events with the same hwTimestampNs produce interval zero, which is excluded.
        Instant now = Instant.now();
        List<EntropyData> events = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            EntropyData e =
                    event(i + 1, 1_000_000L, now.plusMillis(i * 10L)); // Same hardware timestamp.
            e.networkDelayMs = 5L;
            events.add(e);
        }

        var report = service.assessDataQuality(events);
        assertThat(report).isNotNull();
        // Average interval is zero, outside the acceptable range, so decay rate is unrealistic.
        assertThat(report.decayRate().isRealistic()).isFalse();
    }

    /**
     * Creates a minimal {@link EntropyData} fixture with the given sequence number,
     * hardware timestamp, and server reception time. Derived fields (RPI timestamp,
     * TDC timestamp, network delay) are populated with plausible defaults.
     */
    private EntropyData event(long sequence, long hwTimestampNs, Instant serverReceived) {
        EntropyData e = new EntropyData("ts-" + hwTimestampNs, hwTimestampNs, sequence);
        e.serverReceived = serverReceived;
        e.createdAt = serverReceived;
        e.rpiTimestampUs = hwTimestampNs / 1_000; // Convert nanoseconds to microseconds.
        e.tdcTimestampPs = hwTimestampNs * 1_000; // Convert ns to ps
        e.networkDelayMs = 5L;
        e.sourceAddress = "sensor-1";
        return e;
    }
}
