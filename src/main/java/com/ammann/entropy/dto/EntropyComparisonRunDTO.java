/* (C)2026 */
package com.ammann.entropy.dto;

import com.ammann.entropy.model.EntropyComparisonRun;
import java.time.Instant;

/**
 * API representation of a single entropy comparison run.
 *
 * @param id run identifier
 * @param runTimestamp logical execution timestamp of the run
 * @param status current run status
 * @param sp80022SampleSizeBytes configured SP 800-22 sample size in bytes
 * @param sp80090bSampleSizeBytes configured SP 800-90B sample size in bytes
 * @param metricsSampleSizeBytes configured sample size for local metrics
 * @param mixedValid whether mixed entropy injection was considered valid
 * @param mixedInjectionTimestamp timestamp of the mixed injection operation
 * @param createdAt persistence creation timestamp
 * @param completedAt completion timestamp for terminal states
 */
public record EntropyComparisonRunDTO(
        Long id,
        Instant runTimestamp,
        String status,
        Integer sp80022SampleSizeBytes,
        Integer sp80090bSampleSizeBytes,
        Integer metricsSampleSizeBytes,
        Boolean mixedValid,
        Instant mixedInjectionTimestamp,
        Instant createdAt,
        Instant completedAt) {

    /**
     * Converts a run entity to its API DTO counterpart.
     *
     * @param run persisted comparison run entity
     * @return immutable DTO suitable for response serialization
     */
    public static EntropyComparisonRunDTO from(EntropyComparisonRun run) {
        return new EntropyComparisonRunDTO(
                run.id,
                run.runTimestamp,
                run.status != null ? run.status.name() : null,
                run.sp80022SampleSizeBytes,
                run.sp80090bSampleSizeBytes,
                run.metricsSampleSizeBytes,
                run.mixedValid,
                run.mixedInjectionTimestamp,
                run.createdAt,
                run.completedAt);
    }
}
