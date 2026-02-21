/* (C)2026 */
package com.ammann.entropy.dto;

import java.time.Instant;
import java.util.List;

/**
 * Summary response for entropy source comparison history.
 *
 * <p>The payload combines the latest run state with a lightweight aggregate of
 * completed-run volume for dashboard-style views.
 *
 * @param latestRunId identifier of the most recent run, if available
 * @param latestRunTimestamp start timestamp of the most recent run
 * @param latestRunStatus status of the most recent run
 * @param latestResults per-source results for the most recent run
 * @param totalRunsCompleted total number of completed comparison runs
 */
public record EntropyComparisonSummaryDTO(
        Long latestRunId,
        Instant latestRunTimestamp,
        String latestRunStatus,
        List<EntropyComparisonResultDTO> latestResults,
        long totalRunsCompleted) {}
