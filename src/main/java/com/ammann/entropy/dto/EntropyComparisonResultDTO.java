/* (C)2026 */
package com.ammann.entropy.dto;

import com.ammann.entropy.model.EntropyComparisonResult;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * API projection of comparison metrics for a single entropy source within one run.
 *
 * <p>The DTO combines statistical test outcomes, entropy estimates, and metadata
 * required by monitoring and historical analysis endpoints.
 *
 * @param id database identifier of the result row
 * @param comparisonRunId parent comparison run identifier
 * @param sourceType compared entropy source category
 * @param bytesCollected total bytes collected for this source
 * @param sp80022BytesUsed bytes used for NIST SP 800-22 analysis
 * @param sp80090bBytesUsed bytes used for NIST SP 800-90B analysis
 * @param metricsBytesUsed bytes used for auxiliary entropy metrics
 * @param nist22PassRate pass rate across executed SP 800-22 tests
 * @param nist22PValueMean mean p-value across executed SP 800-22 tests
 * @param nist22PValueMin minimum p-value across executed SP 800-22 tests
 * @param nist22ExecutedTests number of SP 800-22 tests executed
 * @param nist22SkippedTests number of SP 800-22 tests skipped
 * @param nist22Status aggregated SP 800-22 status
 * @param minEntropyEstimate minimum entropy estimate from SP 800-90B
 * @param nist90bStatus aggregated SP 800-90B status
 * @param shannonEntropy Shannon entropy estimate
 * @param renyiEntropy Renyi entropy estimate
 * @param sampleEntropy sample entropy estimate
 * @param createdAt result creation timestamp
 */
public record EntropyComparisonResultDTO(
        Long id,
        Long comparisonRunId,
        String sourceType,
        Integer bytesCollected,
        Integer sp80022BytesUsed,
        Integer sp80090bBytesUsed,
        Integer metricsBytesUsed,
        BigDecimal nist22PassRate,
        BigDecimal nist22PValueMean,
        BigDecimal nist22PValueMin,
        Integer nist22ExecutedTests,
        Integer nist22SkippedTests,
        String nist22Status,
        BigDecimal minEntropyEstimate,
        String nist90bStatus,
        BigDecimal shannonEntropy,
        BigDecimal renyiEntropy,
        BigDecimal sampleEntropy,
        Instant createdAt) {

    /**
     * Maps a persistence entity to its API representation.
     *
     * @param result stored comparison result entity
     * @return immutable DTO for response serialization
     */
    public static EntropyComparisonResultDTO from(EntropyComparisonResult result) {
        return new EntropyComparisonResultDTO(
                result.id,
                result.comparisonRunId,
                result.sourceType != null ? result.sourceType.name() : null,
                result.bytesCollected,
                result.sp80022BytesUsed,
                result.sp80090bBytesUsed,
                result.metricsBytesUsed,
                result.nist22PassRate,
                result.nist22PValueMean,
                result.nist22PValueMin,
                result.nist22ExecutedTests,
                result.nist22SkippedTests,
                result.nist22Status,
                result.minEntropyEstimate,
                result.nist90bStatus,
                result.shannonEntropy,
                result.renyiEntropy,
                result.sampleEntropy,
                result.createdAt);
    }
}
