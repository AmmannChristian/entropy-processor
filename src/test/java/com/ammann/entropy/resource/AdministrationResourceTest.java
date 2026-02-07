/* (C)2026 */
package com.ammann.entropy.resource;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

class AdministrationResourceTest {

    @Test
    void getSystemConfigurationReturnsNotImplemented() {
        AdministrationResource resource = new AdministrationResource();

        Response response = resource.getSystemConfiguration();

        assertThat(response.getStatus()).isEqualTo(Response.Status.NOT_IMPLEMENTED.getStatusCode());
        assertThat(response.getEntity()).isEqualTo("System configuration endpoint not implemented");
    }
}
