package com.ammann.entropy.service;

import com.ammann.entropy.service.EntropyStatisticsService.BasicStatistics;
import com.ammann.entropy.service.EntropyStatisticsService.EntropyAnalysisResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link EntropyStatisticsService}.
 *
 * <p>Validates Shannon entropy, Renyi entropy, sample entropy, and approximate
 * entropy calculations against known distributions, and verifies input
 * validation for edge cases and invalid parameters.
 */
class EntropyStatisticsServiceTest
{

    private final EntropyStatisticsService service = new EntropyStatisticsService();

    @Test
    void shannonEntropyForUniformDistributionIsMaximal()
    {
        List<Long> intervals = List.of(1L, 2L, 3L, 1L, 2L, 3L);

        double entropy = service.calculateShannonEntropy(intervals, 1);

        assertThat(entropy).isCloseTo(Math.log(3) / Math.log(2), withinTolerance());
    }

    @Test
    void renyiEntropyFallsBackToShannonWhenAlphaIsOne()
    {
        List<Long> intervals = LongStream.rangeClosed(1, 10).boxed().toList();

        double shannon = service.calculateShannonEntropy(intervals, 1);
        double renyi = service.calculateRenyiEntropy(intervals, 1.0, 1);

        assertThat(renyi).isCloseTo(shannon, withinTolerance());
    }

    @Test
    void comprehensiveAnalysisMatchesUniformDistributionEntropy()
    {
        List<Long> intervals = LongStream.range(0, 150)
                .map(i -> (i % 5 + 1) * 1_000_000L)
                .boxed()
                .toList();

        EntropyAnalysisResult result = service.calculateAllEntropies(intervals);
        BasicStatistics stats = result.basicStats();
        LongSummaryStatistics summary = intervals.stream().mapToLong(Long::longValue).summaryStatistics();
        double expectedUniformEntropy = Math.log(5) / Math.log(2);

        assertThat(result.sampleCount()).isEqualTo(intervals.size());
        assertThat(result.shannonEntropy()).isCloseTo(expectedUniformEntropy, withinTolerance());
        assertThat(result.renyiEntropy()).isCloseTo(expectedUniformEntropy, withinTolerance());
        assertThat(stats.count()).isEqualTo(summary.getCount());
        assertThat(stats.min()).isEqualTo(summary.getMin());
        assertThat(stats.max()).isEqualTo(summary.getMax());
    }

    @Test
    void comprehensiveEntropyWithFineBucketsAvoidsHistogramCollapse()
    {
        List<Long> intervals = LongStream.range(0, 500)
                .map(i -> 2_700L + (i % 6) * 1_000L)
                .boxed()
                .toList();

        EntropyAnalysisResult coarse = service.calculateAllEntropies(intervals, 1_000_000);
        EntropyAnalysisResult fine = service.calculateAllEntropies(intervals, 1_000);

        assertThat(coarse.shannonEntropy()).isCloseTo(0.0, within(0.0001));
        assertThat(fine.shannonEntropy()).isGreaterThan(0.1);
        assertThat(fine.renyiEntropy()).isGreaterThan(0.1);
    }

    @Test
    void sampleAndApproxEntropyIncreaseWithVariability()
    {
        List<Long> constantIntervals = LongStream.generate(() -> 1_000L)
                .limit(120)
                .boxed()
                .toList();
        List<Long> variedIntervals = LongStream.range(0, 120)
                .map(i -> (i * 7919 % 50_000) + 500L) // wide spread to reduce pattern matches
                .boxed()
                .toList();

        double constantSample = service.calculateSampleEntropy(constantIntervals, 2, 0.05);
        double variedSample = service.calculateSampleEntropy(variedIntervals, 2, 0.05);
        double constantApprox = service.calculateApproximateEntropy(constantIntervals, 2, 0.05);
        double variedApprox = service.calculateApproximateEntropy(variedIntervals, 2, 0.05);

        assertThat(constantSample).isCloseTo(0.0, within(0.01));
        assertThat(constantApprox).isCloseTo(0.0, within(0.01));
        assertThat(variedSample).isGreaterThan(constantSample + 0.01);
        assertThat(variedApprox).isGreaterThan(constantApprox + 0.01);
    }

    @Test
    void rejectsInvalidInput()
    {
        assertThatThrownBy(() -> service.calculateShannonEntropy(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.calculateSampleEntropy(List.of(-1L, 0L, 2L), 2, 0.2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.calculateApproximateEntropy(List.of(-5L, 2L, 3L), 2, 0.2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.calculateShannonEntropy(List.of(1L, 2L, 3L), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.calculateRenyiEntropy(List.of(1L, 2L, 3L), 2.0, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.calculateAllEntropies(List.of(1L, 2L, 3L), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.5, 2.0, 3.0})
    void renyiEntropyStaysAtOneForUniformBinary(double alpha)
    {
        List<Long> intervals = List.of(1L, 1L, 2L, 2L);

        double entropy = service.calculateRenyiEntropy(intervals, alpha, 1);

        assertThat(entropy).isCloseTo(1.0, withinTolerance());
    }

    @ParameterizedTest
    @MethodSource("invalidSampleEntropyArgs")
    void sampleEntropyRejectsInvalidParameters(List<Long> intervals, int m, double r)
    {
        assertThatThrownBy(() -> service.calculateSampleEntropy(intervals, m, r))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static java.util.stream.Stream<Arguments> invalidSampleEntropyArgs()
    {
        return java.util.stream.Stream.of(
                Arguments.of(List.of(1L, 2L), 2, 0.2),
                Arguments.of(List.of(1L, 2L, 3L), 0, 0.2),
                Arguments.of(List.of(1L, 2L, 3L), 2, 0.0)
        );
    }

    /** Returns a 1-percent tolerance used for floating-point assertions. */
    private org.assertj.core.data.Percentage withinTolerance()
    {
        return org.assertj.core.data.Percentage.withPercentage(1.0);
    }
}
