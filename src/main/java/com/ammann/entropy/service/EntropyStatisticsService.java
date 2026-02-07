package com.ammann.entropy.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for computing entropy and statistical measures over radioactive decay intervals.
 *
 * <p>Implements four entropy algorithms:
 * <ul>
 *   <li>Shannon entropy (histogram-based probability distribution)</li>
 *   <li>Renyi entropy (parameterized generalization of Shannon entropy)</li>
 *   <li>Sample entropy (time-series regularity measure, O(n^2) complexity)</li>
 *   <li>Approximate entropy (pattern complexity measure, O(n^2) complexity)</li>
 * </ul>
 *
 * <p>Quadratic-complexity algorithms automatically downsample inputs exceeding
 * 2000 samples to prevent excessive computation time.
 */
@ApplicationScoped
public class EntropyStatisticsService
{

    private static final Logger LOG = Logger.getLogger(EntropyStatisticsService.class);

    // Configuration constants for optimal performance
    private static final int MIN_SAMPLES_FOR_RELIABLE_ENTROPY = 100;
    private static final int DEFAULT_BUCKET_SIZE_NS = 1_000_000; // 1ms buckets in nanoseconds
    private static final double LOG_2 = Math.log(2.0);

    // Maximum samples for O(n²) algorithms (Sample Entropy, Approximate Entropy)
    // Prevents timeout for large datasets while maintaining statistical validity
    private static final int MAX_SAMPLES_FOR_QUADRATIC_ALGORITHMS = 2000;

    /**
     * Calculates Shannon entropy from an interval histogram.
     *
     * <p>Computes H(X) = -sum(p(x) * log2(p(x))) over the histogram bins.
     *
     * @param intervalsNs  list of time intervals in nanoseconds
     * @param bucketSizeNs histogram bucket size in nanoseconds
     * @return Shannon entropy in bits
     * @throws IllegalArgumentException if the interval list is empty or contains invalid values
     */
    public double calculateShannonEntropy(List<Long> intervalsNs, int bucketSizeNs)
    {
        validateInput(intervalsNs, "Shannon entropy");

        Map<Long, Integer> histogram = createHistogram(intervalsNs, bucketSizeNs);
        double totalCount = intervalsNs.size();
        double entropy = 0.0;

        for (Integer count : histogram.values()) {
            if (count > 0) {
                double probability = count / totalCount;
                entropy -= probability * (Math.log(probability) / LOG_2);
            }
        }

        LOG.debugf("Shannon entropy calculated: %.4f bits from %d intervals",
                entropy, intervalsNs.size());

        return entropy;
    }

    /**
     * Convenience method with default bucket size (1 ms).
     */
    public double calculateShannonEntropy(List<Long> intervalsNs)
    {
        return calculateShannonEntropy(intervalsNs, DEFAULT_BUCKET_SIZE_NS);
    }

    /**
     * Calculates Renyi entropy with parameter alpha.
     *
     * <p>Computes H_alpha(X) = 1/(1-alpha) * log2(sum(p_i^alpha)).
     *
     * <p>Special cases: alpha approaching 0 yields max entropy, alpha approaching 1
     * yields Shannon entropy, and alpha approaching infinity yields min-entropy.
     * When alpha is within 1e-10 of 1.0, the Shannon entropy limit is used.
     *
     * @param intervalsNs  list of time intervals in nanoseconds
     * @param alpha        Renyi parameter (must not equal 1)
     * @param bucketSizeNs histogram bucket size in nanoseconds
     * @return Renyi entropy in bits
     */
    public double calculateRenyiEntropy(List<Long> intervalsNs, double alpha, int bucketSizeNs)
    {
        validateInput(intervalsNs, "Rényi entropy");

        if (Math.abs(alpha - 1.0) < 1e-10) {
            LOG.debug("Renyi parameter alpha is approximately 1, using Shannon entropy limit");
            return calculateShannonEntropy(intervalsNs, bucketSizeNs);
        }

        Map<Long, Integer> histogram = createHistogram(intervalsNs, bucketSizeNs);
        double totalCount = intervalsNs.size();
        double sum = 0.0;

        for (Integer count : histogram.values()) {
            if (count > 0) {
                double probability = count / totalCount;
                sum += Math.pow(probability, alpha);
            }
        }

        double entropy = (1.0 / (1.0 - alpha)) * (Math.log(sum) / LOG_2);

        LOG.debugf("Renyi entropy (alpha=%.2f) calculated: %.4f bits from %d intervals",
                alpha, entropy, intervalsNs.size());

        return entropy;
    }

    /**
     * Convenience method with default bucket size (1 ms).
     */
    public double calculateRenyiEntropy(List<Long> intervalsNs, double alpha)
    {
        return calculateRenyiEntropy(intervalsNs, alpha, DEFAULT_BUCKET_SIZE_NS);
    }

    /**
     * Calculates Sample Entropy for time-series regularity analysis.
     * <p>
     * Sample Entropy measures the negative logarithm of conditional probability
     * that patterns of length m that are similar remain similar for length m+1.
     * <p>
     * Lower values indicate more regularity/predictability.
     * Higher values indicate more complexity/randomness.
     * <p>
     * Note: This algorithm has O(n²) complexity. For large datasets (>2000 samples),
     * the data is automatically downsampled to prevent timeout.
     *
     * @param intervalsNs List of time intervals in nanoseconds (will be normalized)
     * @param m           Pattern length (typically 2)
     * @param r           Tolerance for matching (typically 0.1-0.2 of std dev)
     * @return Sample entropy (dimensionless)
     */
    public double calculateSampleEntropy(List<Long> intervalsNs, int m, double r)
    {
        validateInput(intervalsNs, "Sample entropy");

        // Downsample if dataset is too large (O(n²) algorithm)
        List<Long> processedData = downsampleIfNeeded(intervalsNs, MAX_SAMPLES_FOR_QUADRATIC_ALGORITHMS);

        if (m <= 0) {
            throw new IllegalArgumentException("Pattern length m must be positive");
        }

        if (r <= 0) {
            throw new IllegalArgumentException("Tolerance r must be positive");
        }

        // Normalize data to [0,1] range for pattern matching
        List<Double> normalizedData = normalizeToUnitRange(processedData);
        int n = normalizedData.size();

        if (n < m + 1) {
            throw new IllegalArgumentException(
                    String.format("Need at least %d samples for pattern length %d", m + 1, m));
        }

        double a = 0.0; // Template matches of length m+1
        double b = 0.0; // Template matches of length m

        // Count template matches
        for (int i = 0; i < n - m; i++) {
            List<Double> templateM = normalizedData.subList(i, i + m);
            List<Double> templateMPlus1 = (i + m < n) ?
                    normalizedData.subList(i, i + m + 1) : null;

            for (int j = i + 1; j < n - m; j++) {
                List<Double> candidateM = normalizedData.subList(j, j + m);

                if (!templatesMatch(templateM, candidateM, r)) {
                    continue;
                }
                b++;

                if (templateMPlus1 == null || j + m >= n) {
                    continue;
                }

                List<Double> candidateMPlus1 = normalizedData.subList(j, j + m + 1);
                if (!templatesMatch(templateMPlus1, candidateMPlus1, r)) {
                    continue;
                }

                a++;
            }
        }

        double sampleEntropy = (b > 0 && a > 0) ? -Math.log(a / b) : Double.NaN;

        LOG.debugf("Sample entropy (m=%d, r=%.3f) calculated: %.4f from %d intervals (processed: %d)",
                m, r, sampleEntropy, intervalsNs.size(), processedData.size());

        return sampleEntropy;
    }

    /**
     * Convenience method with standard parameters for radioactive decay analysis.
     */
    public double calculateSampleEntropy(List<Long> intervalsNs)
    {
        return calculateSampleEntropy(intervalsNs, 2, 0.2);
    }

    /**
     * Calculates Approximate Entropy (ApEn) for pattern complexity.
     * <p>
     * ApEn quantifies the unpredictability of fluctuations in time-series data.
     * Unlike Sample Entropy, it includes self-matches which introduces bias
     * but makes it more suitable for shorter datasets.
     * <p>
     * Note: This algorithm has O(n²) complexity. For large datasets (>2000 samples),
     * the data is automatically downsampled to prevent timeout.
     *
     * @param intervalsNs List of time intervals in nanoseconds
     * @param m           Pattern length
     * @param r           Tolerance for matching
     * @return Approximate entropy (dimensionless)
     */
    public double calculateApproximateEntropy(List<Long> intervalsNs, int m, double r)
    {
        validateInput(intervalsNs, "Approximate entropy");

        // Downsample if dataset is too large (O(n²) algorithm)
        List<Long> processedData = downsampleIfNeeded(intervalsNs, MAX_SAMPLES_FOR_QUADRATIC_ALGORITHMS);

        List<Double> normalizedData = normalizeToUnitRange(processedData);

        double phiM = calculatePhi(normalizedData, m, r);
        double phiMPlus1 = calculatePhi(normalizedData, m + 1, r);

        double approximateEntropy = phiM - phiMPlus1;

        LOG.debugf("Approximate entropy (m=%d, r=%.3f) calculated: %.4f from %d intervals (processed: %d)",
                m, r, approximateEntropy, intervalsNs.size(), processedData.size());

        return approximateEntropy;
    }

    /**
     * Convenience method with standard parameters.
     */
    public double calculateApproximateEntropy(List<Long> intervalsNs)
    {
        return calculateApproximateEntropy(intervalsNs, 2, 0.2);
    }

    /**
     * Comprehensive entropy analysis with all four algorithms.
     *
     * @param intervalsNs Time intervals in nanoseconds
     * @return Complete entropy analysis result
     */
    public EntropyAnalysisResult calculateAllEntropies(List<Long> intervalsNs)
    {
        validateInput(intervalsNs, "Comprehensive entropy analysis");

        long startTime = System.nanoTime();

        // Calculate all entropy measures
        double shannon = calculateShannonEntropy(intervalsNs);
        double renyi = calculateRenyiEntropy(intervalsNs, 2.0);
        double sampleEntropy = calculateSampleEntropy(intervalsNs);
        double approximateEntropy = calculateApproximateEntropy(intervalsNs);

        // Basic statistical measures
        var stats = calculateBasicStatistics(intervalsNs);

        long processingTimeNanos = System.nanoTime() - startTime;

        LOG.infof("Entropy analysis completed in %.2fms: Shannon=%.3f, Renyi=%.3f, Sample=%.3f, ApEn=%.3f",
                processingTimeNanos / 1_000_000.0, shannon, renyi, sampleEntropy, approximateEntropy);

        return new EntropyAnalysisResult(
                intervalsNs.size(),
                shannon,
                renyi,
                sampleEntropy,
                approximateEntropy,
                stats,
                processingTimeNanos
        );
    }

    /**
     * Validates that the interval list is non-null, non-empty, and contains only
     * positive values. Logs a warning if the sample count is below the recommended
     * minimum for reliable entropy estimation.
     */
    private void validateInput(List<Long> intervals, String algorithmName)
    {
        if (intervals == null || intervals.isEmpty()) {
            throw new IllegalArgumentException(
                    String.format("%s requires non-empty interval list", algorithmName));
        }

        if (intervals.size() < MIN_SAMPLES_FOR_RELIABLE_ENTROPY) {
            LOG.warnf("%s: Only %d samples available, minimum %d recommended for reliable results",
                    algorithmName, intervals.size(), MIN_SAMPLES_FOR_RELIABLE_ENTROPY);
        }

        // Check for invalid values
        long invalidCount = intervals.stream().mapToLong(Long::longValue).filter(x -> x <= 0).count();
        if (invalidCount > 0) {
            throw new IllegalArgumentException(
                    String.format("Found %d invalid (non-positive) intervals in dataset", invalidCount));
        }
    }

    /** Bins interval values into a frequency histogram with the given bucket size. */
    private Map<Long, Integer> createHistogram(List<Long> data, long bucketSize)
    {
        Map<Long, Integer> histogram = new HashMap<>();

        for (Long value : data) {
            Long bucket = (value / bucketSize) * bucketSize;
            histogram.merge(bucket, 1, Integer::sum);
        }

        return histogram;
    }

    /** Normalizes raw interval values to the [0, 1] range via min-max scaling. */
    private List<Double> normalizeToUnitRange(List<Long> data)
    {
        long min = data.stream().mapToLong(Long::longValue).min().orElse(0L);
        long max = data.stream().mapToLong(Long::longValue).max().orElse(1L);
        long range = max - min;

        if (range == 0) {
            return data.stream().map(x -> 0.5).toList();
        }

        return data.stream()
                .map(val -> (double) (val - min) / range)
                .toList();
    }

    /** Returns {@code true} if every element-wise difference is within the given tolerance. */
    private boolean templatesMatch(List<Double> template1, List<Double> template2, double tolerance)
    {
        if (template1.size() != template2.size()) return false;

        for (int i = 0; i < template1.size(); i++) {
            if (Math.abs(template1.get(i) - template2.get(i)) > tolerance) {
                return false;
            }
        }
        return true;
    }

    /**
     * Computes the phi statistic used in approximate entropy. Counts, for each
     * template of length {@code m}, the fraction of matching templates and
     * averages the log of those fractions.
     */
    private double calculatePhi(List<Double> data, int m, double r)
    {
        int n = data.size();
        double phi = 0.0;

        for (int i = 0; i <= n - m; i++) {
            List<Double> template = data.subList(i, i + m);
            int matches = 0;

            for (int j = 0; j <= n - m; j++) {
                List<Double> candidate = data.subList(j, j + m);
                if (templatesMatch(template, candidate, r)) {
                    matches++;
                }
            }

            if (matches > 0) {
                phi += Math.log((double) matches / (n - m + 1));
            }
        }

        return phi / (n - m + 1);
    }

    /** Computes count, sum, min, max, mean, standard deviation, and variance. */
    private BasicStatistics calculateBasicStatistics(List<Long> data)
    {
        var summary = data.stream().mapToLong(Long::longValue).summaryStatistics();

        double mean = summary.getAverage();
        double variance = data.stream()
                .mapToDouble(x -> Math.pow(x - mean, 2))
                .average()
                .orElse(0.0);
        double stdDev = Math.sqrt(variance);

        return new BasicStatistics(
                summary.getCount(),
                summary.getSum(),
                summary.getMin(),
                summary.getMax(),
                mean,
                stdDev,
                variance
        );
    }

    /**
     * Downsamples data if it exceeds the maximum sample size.
     * Uses uniform sampling to preserve statistical properties.
     *
     * @param data Original data
     * @param maxSamples Maximum number of samples to keep
     * @return Downsampled data (or original if already small enough)
     */
    private List<Long> downsampleIfNeeded(List<Long> data, int maxSamples)
    {
        if (data.size() <= maxSamples) {
            return data;
        }

        int step = data.size() / maxSamples;
        LOG.infof("Downsampling %d samples to %d (step=%d) to prevent timeout",
                data.size(), maxSamples, step);

        return java.util.stream.IntStream.range(0, maxSamples)
                .mapToObj(i -> data.get(i * step))
                .toList();
    }

    /**
     * Aggregated result of a comprehensive entropy analysis containing all four
     * entropy measures, basic statistics, and processing metadata.
     */
    public record EntropyAnalysisResult(
            int sampleCount,
            double shannonEntropy,
            double renyiEntropy,
            double sampleEntropy,
            double approximateEntropy,
            BasicStatistics basicStats,
            long processingTimeNanos
    )
    {
    }

    /**
     * Basic descriptive statistics computed over a list of interval values.
     * All temporal values are in the same unit as the input (nanoseconds).
     */
    public record BasicStatistics(
            long count,
            long sum,
            long min,
            long max,
            double mean,
            double standardDeviation,
            double variance
    )
    {
    }
}