/* (C)2026 */
package com.ammann.entropy.support;

import com.ammann.entropy.enumeration.JobStatus;
import com.ammann.entropy.enumeration.ValidationType;
import com.ammann.entropy.grpc.proto.EdgeMetrics;
import com.ammann.entropy.grpc.proto.EntropyBatch;
import com.ammann.entropy.grpc.proto.TDCEvent;
import com.ammann.entropy.model.EntropyData;
import com.ammann.entropy.model.NistValidationJob;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class TestDataFactory {

    private TestDataFactory() {}

    public static EntropyData createEntropyEvent(
            long sequence, long hwTimestampNs, Instant serverReceived) {
        EntropyData data = new EntropyData("ts-" + hwTimestampNs, hwTimestampNs, sequence);
        data.serverReceived = serverReceived;
        data.createdAt = serverReceived;
        data.rpiTimestampUs =
                hwTimestampNs / 1000; // Convert nanoseconds to microseconds for RPI timestamp.
        data.tdcTimestampPs = hwTimestampNs * 1000; // Convert ns to ps for TDC timestamp
        data.networkDelayMs = 2L;
        data.batchId = "batch-" + UUID.randomUUID();
        data.sourceAddress = "test-sensor";
        data.channel = 1;
        data.qualityScore = 1.0;

        return data;
    }

    public static List<EntropyData> buildSequentialEvents(
            int count, long startHwTimestamp, Instant serverReceived) {
        List<EntropyData> events = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            events.add(
                    createEntropyEvent(
                            i + 1L,
                            startHwTimestamp + (i * 1500L),
                            serverReceived.plusMillis(i * 10L)));
        }

        return events;
    }

    public static EntropyBatch buildEntropyBatch(
            int batchSequence, List<TDCEvent> events, EdgeMetrics metrics) {
        EntropyBatch.Builder builder =
                EntropyBatch.newBuilder()
                        .setBatchSequence(batchSequence)
                        .setSourceId("gateway-1")
                        .setBatchTimestampUs(Instant.now().toEpochMilli() * 1000);

        builder.addAllEvents(events);

        if (metrics != null) {
            builder.setMetrics(metrics);
        }

        return builder.build();
    }

    /**
     * Create a NIST validation job with specified state.
     *
     * @param validationType Type of validation (SP_800_22 or SP_800_90B)
     * @param status Job status
     * @param windowStart Start of data window
     * @param windowEnd End of data window
     * @param createdBy Username who created the job
     * @return NIST validation job entity (not persisted)
     */
    public static NistValidationJob createNistValidationJob(
            ValidationType validationType,
            JobStatus status,
            Instant windowStart,
            Instant windowEnd,
            String createdBy) {
        NistValidationJob job = new NistValidationJob();
        job.validationType = validationType;
        job.status = status;
        job.windowStart = windowStart;
        job.windowEnd = windowEnd;
        job.createdBy = createdBy;
        job.createdAt = Instant.now();
        job.progressPercent = 0;
        job.currentChunk = 0;

        if (status == JobStatus.RUNNING) {
            job.startedAt = Instant.now();
        } else if (status == JobStatus.COMPLETED) {
            job.startedAt = Instant.now().minusSeconds(300);
            job.completedAt = Instant.now();
            job.progressPercent = 100;
        } else if (status == JobStatus.FAILED) {
            job.startedAt = Instant.now().minusSeconds(300);
            job.completedAt = Instant.now();
            job.errorMessage = "Test failure";
        }

        return job;
    }

    /**
     * Create a completed NIST validation job with results.
     *
     * @param validationType Type of validation
     * @param windowStart Start of data window
     * @param windowEnd End of data window
     * @param runId Run ID (test suite or assessment ID)
     * @return Completed job entity (not persisted)
     */
    public static NistValidationJob createCompletedNistJob(
            ValidationType validationType, Instant windowStart, Instant windowEnd, UUID runId) {
        NistValidationJob job =
                createNistValidationJob(
                        validationType, JobStatus.COMPLETED, windowStart, windowEnd, "test-user");
        job.setRunId(runId);
        job.totalChunks = 1;
        job.currentChunk = 1;
        return job;
    }

    /**
     * Create a failed NIST validation job with error message.
     *
     * @param validationType Type of validation
     * @param windowStart Start of data window
     * @param windowEnd End of data window
     * @param errorMessage Error message
     * @return Failed job entity (not persisted)
     */
    public static NistValidationJob createFailedNistJob(
            ValidationType validationType,
            Instant windowStart,
            Instant windowEnd,
            String errorMessage) {
        NistValidationJob job =
                createNistValidationJob(
                        validationType, JobStatus.FAILED, windowStart, windowEnd, "test-user");
        job.errorMessage = errorMessage;
        return job;
    }

    /**
     * Build sequential NIST validation jobs for pagination tests.
     *
     * @param count Number of jobs to create
     * @param validationType Type of validation
     * @param status Job status
     * @param createdBy Username who created the jobs
     * @return List of job entities (not persisted)
     */
    public static List<NistValidationJob> buildSequentialJobs(
            int count, ValidationType validationType, JobStatus status, String createdBy) {
        List<NistValidationJob> jobs = new ArrayList<>(count);
        Instant now = Instant.now();

        for (int i = 0; i < count; i++) {
            NistValidationJob job =
                    createNistValidationJob(
                            validationType,
                            status,
                            now.minus(i + 2, ChronoUnit.HOURS),
                            now.minus(i + 1, ChronoUnit.HOURS),
                            createdBy);
            job.createdAt = now.minusSeconds(i * 10L);
            jobs.add(job);
        }

        return jobs;
    }
}
