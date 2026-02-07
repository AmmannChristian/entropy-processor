package com.ammann.entropy.resource;

import com.ammann.entropy.properties.ApiProperties;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

/**
 * REST resource providing administrative and system monitoring endpoints.
 *
 * <p>All endpoints require the {@code ADMIN_ROLE} role.
 */
@Path(ApiProperties.BASE_URL_V1)
@Tag(name = "Administration API", description = "System Administration and Monitoring")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ADMIN_ROLE")
public class AdministrationResource
{
    private static final Logger LOG = Logger.getLogger(AdministrationResource.class);

    /**
     * Get application configuration details.
     */
    @GET
    @Path(ApiProperties.System.CONFIG)
    @Operation(summary = "System Configuration", description = "Get current system configuration")
    public Response getSystemConfiguration(){

        return Response.status(Response.Status.NOT_IMPLEMENTED)
                .entity("System configuration endpoint not implemented")
                .build();
    }
}
