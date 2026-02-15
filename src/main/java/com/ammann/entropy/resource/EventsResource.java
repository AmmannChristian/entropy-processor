/* (C)2026 */
package com.ammann.entropy.resource;

import com.ammann.entropy.dto.*;
import com.ammann.entropy.dto.RecentEventsResponseDTO.EventSummaryDTO;
import com.ammann.entropy.exception.ValidationException;
import com.ammann.entropy.model.EntropyData;
import com.ammann.entropy.properties.ApiProperties;
import com.ammann.entropy.service.DataQualityService;
import com.ammann.entropy.service.EntropyStatisticsService;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

/**
 * REST resource for querying and analyzing entropy event data.
 *
 * <p>Provides endpoints for retrieving recent events, counting events in time windows,
 * computing interval statistics, assessing data quality, and monitoring event rates.
 *
 * <p>Requires {@code ADMIN_ROLE} or {@code USER_ROLE} unless an endpoint is
 * explicitly annotated with {@code @PermitAll}.
 */
@Path(ApiProperties.BASE_URL_V1)
@Tag(name = "Events API", description = "Entropy event data access and statistics")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN_ROLE", "USER_ROLE"})
public class EventsResource {

    private static final Logger LOG = Logger.getLogger(EventsResource.class);
    private static final int DEFAULT_RECENT_COUNT = 100;
    private static final int MAX_RECENT_COUNT = 10000;
    private static final Duration DEFAULT_TIME_WINDOW = Duration.ofHours(1);

    @Inject DataQualityService dataQualityService;

    @Inject EntropyStatisticsService entropyStatisticsService;

    @ConfigProperty(name = "entropy.source.expected-rate-hz", defaultValue = "184.0")
    double expectedRateHz;

    @GET
    @Path(ApiProperties.Events.RECENT)
    @Operation(
            summary = "Get Recent Events (Authenticated)",
            description =
                    "Returns detailed entropy events including timing and quality metrics. Requires"
                        + " authentication. For public access, use /api/v1/public/recent-activity.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Recent events retrieved successfully",
                content =
                        @Content(schema = @Schema(implementation = RecentEventsResponseDTO.class))),
        @APIResponse(responseCode = "400", description = "Invalid parameters"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getRecentEvents(
            @Parameter(description = "Number of recent events to return (max 10000)")
                    @QueryParam("count")
                    @DefaultValue("100")
                    int count) {

        LOG.debugf("Recent events request: count=%d", count);

        if (count <= 0) {
            throw ValidationException.invalidParameter("count", count, "positive integer");
        }
        if (count > MAX_RECENT_COUNT) {
            throw ValidationException.invalidParameter(
                    "count", count, "value less than or equal to " + MAX_RECENT_COUNT);
        }

        List<EntropyData> events =
                EntropyData.find("ORDER BY hwTimestampNs DESC").range(0, count - 1).list();

        if (events.isEmpty()) {
            return Response.ok(new RecentEventsResponseDTO(List.of(), 0, null, null)).build();
        }

        List<EntropyData> sortedEvents =
                events.stream()
                        .sorted((a, b) -> Long.compare(a.hwTimestampNs, b.hwTimestampNs))
                        .toList();

        List<EventSummaryDTO> eventSummaries = new ArrayList<>();
        Long previousTimestamp = null;

        for (EntropyData event : sortedEvents) {
            Long intervalToPrevious = null;
            if (previousTimestamp != null) {
                intervalToPrevious = event.hwTimestampNs - previousTimestamp;
            }

            eventSummaries.add(
                    new EventSummaryDTO(
                            event.id,
                            event.hwTimestampNs,
                            event.sequenceNumber,
                            event.serverReceived,
                            event.networkDelayMs,
                            event.qualityScore,
                            intervalToPrevious));

            previousTimestamp = event.hwTimestampNs;
        }

        Instant oldest = sortedEvents.getFirst().serverReceived;
        Instant newest = sortedEvents.getLast().serverReceived;

        var response =
                new RecentEventsResponseDTO(eventSummaries, eventSummaries.size(), oldest, newest);

        LOG.infof("Returned %d recent events from %s to %s", eventSummaries.size(), oldest, newest);
        return Response.ok(response).build();
    }

    @GET
    @Path(ApiProperties.Events.COUNT)
    @Operation(
            summary = "Count Events in Time Window",
            description = "Returns the number of entropy events in a specified time window")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Event count retrieved successfully",
                content = @Content(schema = @Schema(implementation = EventCountResponseDTO.class))),
        @APIResponse(responseCode = "400", description = "Invalid parameters"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getEventCount(
            @Parameter(description = "Start of time window (ISO-8601)") @QueryParam("from")
                    String from,
            @Parameter(description = "End of time window (ISO-8601)") @QueryParam("to") String to) {

        LOG.debugf("Event count request: from=%s, to=%s", from, to);

        TimeWindow window = parseTimeWindow(from, to);

        long count =
                EntropyData.count("serverReceived BETWEEN ?1 AND ?2", window.start, window.end);
        long durationSeconds = Duration.between(window.start, window.end).toSeconds();

        var response = new EventCountResponseDTO(count, window.start, window.end, durationSeconds);

        LOG.infof("Event count: %d events in %d seconds", count, durationSeconds);
        return Response.ok(response).build();
    }

    @GET
    @Path(ApiProperties.Events.STATISTICS)
    @Operation(
            summary = "Get Aggregated Statistics",
            description = "Returns aggregated statistics for entropy events in a time window")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Statistics retrieved successfully",
                content = @Content(schema = @Schema(implementation = IntervalStatisticsDTO.class))),
        @APIResponse(responseCode = "400", description = "Invalid parameters or insufficient data"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getStatistics(
            @Parameter(description = "Start of time window (ISO-8601)") @QueryParam("from")
                    String from,
            @Parameter(description = "End of time window (ISO-8601)") @QueryParam("to") String to) {

        LOG.debugf("Statistics request: from=%s, to=%s", from, to);

        TimeWindow window = parseTimeWindow(from, to);
        var stats = EntropyData.calculateIntervalStats(window.start, window.end);

        if (stats.count() == 0) {
            throw ValidationException.insufficientData("entropy events", 2, 0);
        }

        double cv = stats.meanNs() > 0.0 ? stats.stdDevNs() / stats.meanNs() : 0.0;
        var response =
                new IntervalStatisticsDTO(
                        stats.count(),
                        stats.meanNs(),
                        stats.stdDevNs(),
                        stats.minNs(),
                        stats.maxNs(),
                        stats.medianNs(),
                        cv,
                        window.start,
                        window.end);

        LOG.infof(
                "Statistics calculated: %d intervals, mean=%.2f ns, stdDev=%.2f ns",
                response.count(), response.meanNs(), response.stdDevNs());
        return Response.ok(response).build();
    }

    @GET
    @Path(ApiProperties.Events.INTERVALS)
    @Operation(
            summary = "Get Interval Statistics",
            description = "Returns detailed interval statistics between consecutive decay events")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Interval statistics retrieved successfully",
                content = @Content(schema = @Schema(implementation = IntervalStatisticsDTO.class))),
        @APIResponse(responseCode = "400", description = "Invalid parameters or insufficient data"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getIntervalStatistics(
            @Parameter(description = "Start of time window (ISO-8601)") @QueryParam("from")
                    String from,
            @Parameter(description = "End of time window (ISO-8601)") @QueryParam("to") String to) {

        LOG.debugf("Interval statistics request: from=%s, to=%s", from, to);

        TimeWindow window = parseTimeWindow(from, to);
        var stats = EntropyData.calculateIntervalStats(window.start, window.end);

        if (stats.count() == 0) {
            throw ValidationException.insufficientData("intervals", 1, 0);
        }

        double cv = stats.meanNs() > 0.0 ? stats.stdDevNs() / stats.meanNs() : 0.0;
        var response =
                new IntervalStatisticsDTO(
                        stats.count(),
                        stats.meanNs(),
                        stats.stdDevNs(),
                        stats.minNs(),
                        stats.maxNs(),
                        stats.medianNs(),
                        cv,
                        window.start,
                        window.end);

        LOG.infof(
                "Interval statistics: %d intervals, CV=%.4f",
                response.count(), response.coefficientOfVariation());
        return Response.ok(response).build();
    }

    @GET
    @Path(ApiProperties.Events.QUALITY)
    @Operation(
            summary = "Get Data Quality Report",
            description =
                    "Returns a comprehensive data quality assessment including packet loss, clock"
                            + " drift, and decay rate validation")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Quality report generated successfully",
                content = @Content(schema = @Schema(implementation = DataQualityReportDTO.class))),
        @APIResponse(responseCode = "400", description = "Invalid parameters or insufficient data"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getQualityReport(
            @Parameter(description = "Start of time window (ISO-8601)") @QueryParam("from")
                    String from,
            @Parameter(description = "End of time window (ISO-8601)") @QueryParam("to") String to) {

        LOG.debugf("Quality report request: from=%s, to=%s", from, to);

        TimeWindow window = parseTimeWindow(from, to);
        List<EntropyData> events = EntropyData.findInTimeWindow(window.start, window.end);

        if (events.isEmpty()) {
            throw ValidationException.insufficientData("entropy events", 10, 0);
        }

        DataQualityReportDTO report = dataQualityService.assessDataQuality(events);

        if (report == null) {
            throw ValidationException.insufficientData(
                    "entropy events for quality assessment", 10, events.size());
        }

        LOG.infof(
                "Quality report: %d events, score=%.3f, missing=%d",
                report.totalEvents(), report.qualityScore(), report.missingSequenceCount());
        return Response.ok(report).build();
    }

    @GET
    @Path(ApiProperties.Events.RATE)
    @Operation(
            summary = "Get Event Rate",
            description =
                    "Returns the event rate in Hz with comparison to the expected detector count"
                            + " rate (configurable via entropy.source.expected-rate-hz)")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Event rate calculated successfully",
                content = @Content(schema = @Schema(implementation = EventRateResponseDTO.class))),
        @APIResponse(responseCode = "400", description = "Invalid parameters"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    @PermitAll
    public Response getEventRate(
            @Parameter(description = "Start of time window (ISO-8601)") @QueryParam("from")
                    String from,
            @Parameter(description = "End of time window (ISO-8601)") @QueryParam("to") String to) {

        LOG.debugf("Event rate request: from=%s, to=%s", from, to);

        TimeWindow window = parseTimeWindow(from, to);
        long count =
                EntropyData.count("serverReceived BETWEEN ?1 AND ?2", window.start, window.end);

        var response = EventRateResponseDTO.create(count, window.start, window.end, expectedRateHz);

        LOG.infof(
                "Event rate: %.2f Hz (expected: %.2f Hz, deviation: %.2f%%)",
                response.averageRateHz(), response.expectedRateHz(), response.deviationPercent());
        return Response.ok(response).build();
    }

    @GET
    @Path(ApiProperties.Events.INTERVAL_HISTOGRAM)
    @Operation(
            summary = "Get Interval Histogram",
            description =
                    "Returns a histogram of frequencies for intervals between decay events."
                        + " Requires at least 100 intervals for meaningful statistical analysis."
                        + " Default bucket size (100ns) is optimized for radioactive decay"
                        + " intervals in the 2-10µs range.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Histogram computed successfully",
                content = @Content(schema = @Schema(implementation = IntervalHistogramDTO.class))),
        @APIResponse(responseCode = "400", description = "Invalid parameters or insufficient data"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getIntervalHistogram(
            @Parameter(description = "Start of time window (ISO-8601)") @QueryParam("from")
                    String from,
            @Parameter(description = "End of time window (ISO-8601)") @QueryParam("to") String to,
            @Parameter(description = "Bucket size in nanoseconds (must be positive, default: 100)")
                    @QueryParam("bucketSizeNs")
                    @DefaultValue("100")
                    int bucketSizeNs) {

        LOG.debugf(
                "Interval histogram request: from=%s, to=%s, bucketSize=%d",
                from, to, bucketSizeNs);

        // Validate bucket size to prevent division by zero and negative values
        if (bucketSizeNs <= 0) {
            throw ValidationException.invalidParameter(
                    "bucketSizeNs", bucketSizeNs, "positive integer");
        }

        TimeWindow window = parseTimeWindow(from, to);
        List<Long> intervals = EntropyData.calculateIntervals(window.start, window.end);

        if (intervals.size() < 100) {
            throw ValidationException.insufficientData("intervals", 100, intervals.size());
        }

        Map<Long, Integer> histogram =
                entropyStatisticsService.createHistogram(intervals, bucketSizeNs);
        IntervalHistogramDTO response =
                IntervalHistogramDTO.from(
                        histogram, intervals, bucketSizeNs, window.start, window.end);

        LOG.infof(
                "Histogram: %d buckets from %d intervals (min=%dns, max=%dns)",
                histogram.size(),
                intervals.size(),
                response.minIntervalNs(),
                response.maxIntervalNs());
        return Response.ok(response).build();
    }

    /**
     * Parses optional ISO-8601 query parameters into a time window.
     * Defaults to the last hour if parameters are absent.
     *
     * @param from start of the window (ISO-8601), or {@code null} for default
     * @param to   end of the window (ISO-8601), or {@code null} for now
     * @return a validated {@code TimeWindow}
     * @throws ValidationException if timestamps are malformed or inverted
     */
    private TimeWindow parseTimeWindow(String from, String to) {
        Instant end = Instant.now();
        Instant start = end.minus(DEFAULT_TIME_WINDOW);

        if (to != null && !to.isBlank()) {
            try {
                end = Instant.parse(to);
            } catch (DateTimeParseException e) {
                throw ValidationException.invalidParameter("to", to, "ISO-8601 timestamp");
            }
        }

        if (from != null && !from.isBlank()) {
            try {
                start = Instant.parse(from);
            } catch (DateTimeParseException e) {
                throw ValidationException.invalidParameter("from", from, "ISO-8601 timestamp");
            }
        }

        if (start.isAfter(end)) {
            throw ValidationException.invalidParameter("from", from, "timestamp before to");
        }

        return new TimeWindow(start, end);
    }

    private record TimeWindow(Instant start, Instant end) {}
}
