/* (C)2026 */
package com.ammann.entropy.resource;

import com.ammann.entropy.dto.PublicActivityResponseDTO;
import com.ammann.entropy.dto.PublicActivityResponseDTO.PublicEventSummaryDTO;
import com.ammann.entropy.model.EntropyData;
import com.ammann.entropy.properties.ApiProperties;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

@Path(ApiProperties.BASE_URL_V1)
@Tag(name = "Public API", description = "Unauthenticated public status endpoints")
@Produces(MediaType.APPLICATION_JSON)
@PermitAll
public class PublicEventsResource {

    private static final Logger LOG = Logger.getLogger(PublicEventsResource.class);
    private static final int MAX_PUBLIC_EVENTS = 10;
    private static final int DEFAULT_PUBLIC_EVENTS = 5;

    @GET
    @Path(ApiProperties.Public.RECENT_ACTIVITY)
    @Operation(
            summary = "Get Recent Activity (Public)",
            description =
                    "Returns minimal public event activity. Limited to 10 events and safe fields"
                            + " only.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Recent activity retrieved successfully",
                content =
                        @Content(
                                schema =
                                        @Schema(implementation = PublicActivityResponseDTO.class))),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getRecentActivity(
            @Parameter(description = "Number of recent events to return (max 10, default 5)")
                    @QueryParam("count")
                    @DefaultValue("5")
                    int count) {

        LOG.debugf("Public recent activity request: count=%d", count);

        if (count <= 0 || count > MAX_PUBLIC_EVENTS) {
            count = DEFAULT_PUBLIC_EVENTS;
            LOG.debugf("Invalid public count parameter, using default: %d", count);
        }

        List<EntropyData> events =
                EntropyData.find("ORDER BY hwTimestampNs DESC").range(0, count - 1).list();

        if (events.isEmpty()) {
            return Response.ok(new PublicActivityResponseDTO(List.of(), 0, null)).build();
        }

        List<PublicEventSummaryDTO> publicEvents =
                events.stream()
                        .sorted((a, b) -> Long.compare(a.hwTimestampNs, b.hwTimestampNs))
                        .map(
                                event ->
                                        new PublicEventSummaryDTO(
                                                event.id,
                                                event.sequenceNumber,
                                                event.serverReceived))
                        .toList();

        Instant latestActivity =
                events.stream()
                        .map(event -> event.serverReceived)
                        .max(Instant::compareTo)
                        .orElse(null);

        PublicActivityResponseDTO response =
                new PublicActivityResponseDTO(publicEvents, publicEvents.size(), latestActivity);

        LOG.infof(
                "Returned %d public activity events (latest: %s)",
                publicEvents.size(), latestActivity);
        return Response.ok(response).build();
    }
}
