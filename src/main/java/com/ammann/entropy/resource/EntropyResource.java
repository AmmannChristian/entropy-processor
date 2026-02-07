package com.ammann.entropy.resource;

import com.ammann.entropy.dto.*;
import com.ammann.entropy.exception.NistException;
import com.ammann.entropy.exception.ValidationException;
import com.ammann.entropy.model.EntropyData;
import com.ammann.entropy.properties.ApiProperties;
import com.ammann.entropy.service.EntropyStatisticsService;
import com.ammann.entropy.service.NistValidationService;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * REST resource for entropy calculations on radioactive decay interval data.
 *
 * <p>Provides endpoints for Shannon entropy, Renyi entropy, comprehensive entropy
 * analysis, time-window analysis, and NIST SP 800-22 validation. All entropy
 * calculations operate on inter-event intervals stored in TimescaleDB.
 *
 * <p>Requires {@code ADMIN_ROLE} or {@code USER_ROLE} unless an endpoint is
 * explicitly annotated with {@code @PermitAll}.
 */
@Path(ApiProperties.BASE_URL_V1)
@Tag(name = "Entropy API", description = "Decay-based High Entropy Source Analysis")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN_ROLE", "USER_ROLE"})
public class EntropyResource {

    private static final Logger LOG = Logger.getLogger(EntropyResource.class);
    private static final int MIN_INTERVALS_REQUIRED = 100;
    private static final Duration DEFAULT_TIME_WINDOW = Duration.ofHours(1);

    @Inject
    EntropyStatisticsService entropyStatisticsService;

    @Inject
    NistValidationService nistValidationService;

    @GET
    @Path(ApiProperties.Entropy.SHANNON)
    @Operation(
            summary = "Calculate Shannon Entropy",
            description = "Calculates Shannon entropy from radioactive decay intervals using histogram-based probability distribution"
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Shannon entropy calculated successfully",
                    content = @Content(schema = @Schema(implementation = ShannonEntropyResponseDTO.class))),
            @APIResponse(responseCode = "400", description = "Invalid parameters or insufficient data"),
            @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getShannonEntropy(
            @Parameter(description = "Start of time window (ISO-8601)")
            @QueryParam("from") String from,
            @Parameter(description = "End of time window (ISO-8601)")
            @QueryParam("to") String to,
            @Parameter(description = "Histogram bucket size in nanoseconds (default: 1000000 = 1ms)")
            @QueryParam("bucketSize") @DefaultValue("1000000") int bucketSize) {

        LOG.debugf("Shannon entropy request: from=%s, to=%s, bucketSize=%d", from, to, bucketSize);

        TimeWindow window = parseTimeWindow(from, to);
        List<Long> intervals = getIntervalsForWindow(window.start, window.end);

        double shannonEntropy = entropyStatisticsService.calculateShannonEntropy(intervals, bucketSize);

        var response = new ShannonEntropyResponseDTO(
                shannonEntropy,
                (long) intervals.size(),
                window.start,
                window.end,
                bucketSize
        );

        LOG.infof("Shannon entropy calculated: %.4f bits from %d intervals", shannonEntropy, intervals.size());
        return Response.ok(response).build();
    }

    @GET
    @Path(ApiProperties.Entropy.RENYI)
    @Operation(
            summary = "Calculate Renyi Entropy",
            description = "Calculates Renyi entropy with customizable alpha parameter. Alpha equals 1 approximates Shannon entropy, alpha equals 2 gives collision entropy"
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Renyi entropy calculated successfully",
                    content = @Content(schema = @Schema(implementation = RenyiEntropyResponseDTO.class))),
            @APIResponse(responseCode = "400", description = "Invalid parameters or insufficient data"),
            @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getRenyiEntropy(
            @Parameter(description = "Start of time window (ISO-8601)")
            @QueryParam("from") String from,
            @Parameter(description = "End of time window (ISO-8601)")
            @QueryParam("to") String to,
            @Parameter(description = "Renyi parameter alpha (must be positive, alpha near 1 approximates Shannon)")
            @QueryParam("alpha") @DefaultValue("2.0") double alpha,
            @Parameter(description = "Histogram bucket size in nanoseconds (default: 1000000 = 1ms)")
            @QueryParam("bucketSize") @DefaultValue("1000000") int bucketSize) {

        LOG.debugf("Renyi entropy request: from=%s, to=%s, alpha=%.2f, bucketSize=%d", from, to, alpha, bucketSize);

        if (alpha <= 0) {
            throw ValidationException.invalidParameter("alpha", alpha, "positive value");
        }

        TimeWindow window = parseTimeWindow(from, to);
        List<Long> intervals = getIntervalsForWindow(window.start, window.end);

        double renyiEntropy = entropyStatisticsService.calculateRenyiEntropy(intervals, alpha, bucketSize);

        var response = new RenyiEntropyResponseDTO(
                renyiEntropy,
                alpha,
                (long) intervals.size(),
                window.start,
                window.end,
                bucketSize
        );

        LOG.infof("Renyi entropy (alpha=%.2f) calculated: %.4f bits from %d intervals", alpha, renyiEntropy, intervals.size());
        return Response.ok(response).build();
    }

    @GET
    @Path(ApiProperties.Entropy.COMPREHENSIVE)
    @Operation(
            summary = "Comprehensive Entropy Analysis",
            description = "Calculates all entropy measures: Shannon, Renyi (alpha=2), Sample Entropy, and Approximate Entropy"
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Comprehensive entropy analysis completed",
                    content = @Content(schema = @Schema(implementation = EntropyStatisticsDTO.class))),
            @APIResponse(responseCode = "400", description = "Invalid parameters or insufficient data"),
            @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getComprehensiveEntropy(
            @Parameter(description = "Start of time window (ISO-8601)")
            @QueryParam("from") String from,
            @Parameter(description = "End of time window (ISO-8601)")
            @QueryParam("to") String to) {

        LOG.debugf("Comprehensive entropy request: from=%s, to=%s", from, to);

        TimeWindow window = parseTimeWindow(from, to);
        List<Long> intervals = getIntervalsForWindow(window.start, window.end);

        var result = entropyStatisticsService.calculateAllEntropies(intervals);
        var dto = EntropyStatisticsDTO.from(result, window.start, window.end);

        LOG.infof("Comprehensive entropy analysis completed in %.2fms for %d intervals",
                result.processingTimeNanos() / 1_000_000.0, intervals.size());
        return Response.ok(dto).build();
    }

    @GET
    @Path(ApiProperties.Entropy.WINDOW)
    @Operation(
            summary = "Time Window Entropy Analysis",
            description = "Analyzes entropy for a specific time window with all available metrics"
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Time window analysis completed",
                    content = @Content(schema = @Schema(implementation = EntropyStatisticsDTO.class))),
            @APIResponse(responseCode = "400", description = "Invalid time window parameters"),
            @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getWindowAnalysis(
            @Parameter(description = "Start of time window (ISO-8601)", required = true)
            @QueryParam("from") String from,
            @Parameter(description = "End of time window (ISO-8601)", required = true)
            @QueryParam("to") String to) {

        if (from == null || to == null) {
            throw ValidationException.invalidParameter("from/to", "null", "ISO-8601 timestamps");
        }

        LOG.debugf("Time window analysis request: from=%s, to=%s", from, to);

        TimeWindow window = parseTimeWindow(from, to);
        List<Long> intervals = getIntervalsForWindow(window.start, window.end);

        var result = entropyStatisticsService.calculateAllEntropies(intervals);
        var dto = EntropyStatisticsDTO.from(result, window.start, window.end);

        LOG.infof("Window analysis completed: %d intervals from %s to %s",
                intervals.size(), window.start, window.end);
        return Response.ok(dto).build();
    }

    @GET
    @Path(ApiProperties.Entropy.NIST_LATEST)
    @Operation(
            summary = "Latest NIST Validation Results",
            description = "Returns the most recent NIST SP 800-22 test suite results"
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Latest NIST results retrieved",
                    content = @Content(schema = @Schema(implementation = NISTSuiteResultDTO.class))),
            @APIResponse(responseCode = "404", description = "No NIST validation results available"),
            @APIResponse(responseCode = "500", description = "Internal server error")
    })
    @PermitAll
    public Response getLatestNISTResults() {
        LOG.debug("Latest NIST validation results request");

        NISTSuiteResultDTO result = nistValidationService.getLatestValidationResult();

        if (result == null) {
            LOG.debug("No NIST validation results available");
            return Response.ok().build();
        }

        LOG.infof("Latest NIST results: %d/%d tests passed, executed at %s",
                result.passedTests(), result.totalTests(), result.executedAt());
        return Response.ok(result).build();
    }

    @POST
    @Path(ApiProperties.Entropy.NIST_VALIDATE)
    @Operation(
            summary = "Trigger NIST Validation",
            description = "Manually triggers NIST SP 800-22 validation for a specific time window"
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "NIST validation completed",
                    content = @Content(schema = @Schema(implementation = NISTSuiteResultDTO.class))),
            @APIResponse(responseCode = "400", description = "Invalid parameters or insufficient data"),
            @APIResponse(responseCode = "503", description = "NIST validation service unavailable")
    })
    public Response triggerNISTValidation(
            @Parameter(description = "Start of validation window (ISO-8601)")
            @QueryParam("from") String from,
            @Parameter(description = "End of validation window (ISO-8601)")
            @QueryParam("to") String to,
            @HeaderParam("Authorization") String authHeader) {

        LOG.infof("Manual NIST validation triggered: from=%s, to=%s", from, to);

        TimeWindow window = parseTimeWindow(from, to);

        // Extract bearer token for propagation to NIST gRPC services
        String bearerToken = extractBearerToken(authHeader);

        try {
            NISTSuiteResultDTO result = nistValidationService.validateTimeWindow(
                    window.start, window.end, bearerToken);

            LOG.infof("NIST validation completed: %d/%d tests passed, recommendation: %s",
                    result.passedTests(), result.totalTests(), result.getRecommendation());
            return Response.ok(result).build();

        } catch (NistException e) {
            LOG.errorf(e, "NIST validation failed");
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(new ErrorResponseDTO(e.getMessage(), "NIST_SERVICE_ERROR"))
                    .build();
        }
    }

    /**
     * Extracts the bearer token from an Authorization header.
     *
     * @param authHeader The Authorization header value (e.g., "Bearer xyz123")
     * @return The token without the "Bearer " prefix, or null if not present
     */
    private String extractBearerToken(String authHeader) {
        if (authHeader != null && authHeader.toLowerCase().startsWith("bearer ")) {
            return authHeader.substring(7).trim();
        }
        return null;
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

    /**
     * Retrieves inter-event intervals for the given time window and validates
     * that a minimum number of intervals is available.
     *
     * @param start start of the query window
     * @param end   end of the query window
     * @return list of interval durations in nanoseconds
     * @throws ValidationException if no intervals are available
     */
    private List<Long> getIntervalsForWindow(Instant start, Instant end) {
        List<Long> intervals = EntropyData.calculateIntervals(start, end);

        if (intervals.isEmpty()) {
            throw ValidationException.insufficientData("entropy intervals", MIN_INTERVALS_REQUIRED, 0);
        }

        if (intervals.size() < MIN_INTERVALS_REQUIRED) {
            LOG.warnf("Only %d intervals available, %d recommended for reliable results",
                    intervals.size(), MIN_INTERVALS_REQUIRED);
        }

        return intervals;
    }

    /** Immutable pair of start and end instants defining a query window. */
    private record TimeWindow(Instant start, Instant end) {}
}