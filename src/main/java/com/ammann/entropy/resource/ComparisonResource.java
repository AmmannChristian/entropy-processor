/* (C)2026 */
package com.ammann.entropy.resource;

import com.ammann.entropy.dto.EntropyComparisonResultDTO;
import com.ammann.entropy.dto.EntropyComparisonRunDTO;
import com.ammann.entropy.dto.EntropyComparisonSummaryDTO;
import com.ammann.entropy.properties.ApiProperties;
import com.ammann.entropy.service.EntropyComparisonService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

/**
 * REST resource exposing entropy source comparison endpoints.
 */
@Path(ApiProperties.BASE_URL_V1 + ApiProperties.Comparison.BASE)
@Tag(name = "Entropy Comparison API", description = "Entropy source comparison runs and results")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN_ROLE", "USER_ROLE"})
public class ComparisonResource {

    private static final Logger LOG = Logger.getLogger(ComparisonResource.class);
    private static final int MAX_LIMIT = 50;

    @Inject EntropyComparisonService comparisonService;

    @Inject
    @Named("nist-validation-executor")
    ManagedExecutor executor;

    @GET
    @Path("/results")
    @Operation(
            summary = "Recent comparison runs",
            description = "Returns the most recent comparison runs")
    public Response getRecentRuns(@QueryParam("limit") @DefaultValue("10") int limit) {
        int effectiveLimit = Math.min(Math.max(1, limit), MAX_LIMIT);
        List<EntropyComparisonRunDTO> runs =
                comparisonService.getRecentRuns(effectiveLimit).stream()
                        .map(EntropyComparisonRunDTO::from)
                        .toList();
        return Response.ok(runs).build();
    }

    @GET
    @Path("/{runId}/results")
    @Operation(
            summary = "Results for a specific run",
            description = "Returns all source results for a comparison run")
    public Response getRunResults(@PathParam("runId") Long runId) {
        List<EntropyComparisonResultDTO> results =
                comparisonService.getResultsForRun(runId).stream()
                        .map(EntropyComparisonResultDTO::from)
                        .toList();
        return Response.ok(results).build();
    }

    @GET
    @Path("/summary")
    @Operation(
            summary = "Comparison summary",
            description = "Returns a summary of the latest comparison run")
    public Response getSummary() {
        EntropyComparisonSummaryDTO summary = comparisonService.getSummary();
        return Response.ok(summary).build();
    }

    @POST
    @Path("/trigger")
    @RolesAllowed("ADMIN_ROLE")
    @Operation(
            summary = "Trigger comparison run",
            description = "Starts an immediate comparison run asynchronously")
    public Response triggerComparison() {
        LOG.info("Manual comparison run triggered");
        executor.submit(
                () -> {
                    try {
                        comparisonService.runComparison();
                    } catch (Exception e) {
                        LOG.errorf(e, "Manual comparison run failed");
                    }
                });
        return Response.accepted(java.util.Map.of("message", "Comparison run started")).build();
    }
}
