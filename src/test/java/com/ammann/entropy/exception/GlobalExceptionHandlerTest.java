package com.ammann.entropy.exception;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest
{

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp()
    {
        handler = new GlobalExceptionHandler();
        handler.uriInfo = null; // default path handling
    }

    @Test
    void mapsValidationExceptionToBadRequest()
    {
        Response response = handler.toResponse(new ValidationException("bad input"));

        assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
        GlobalExceptionHandler.ErrorResponse body = (GlobalExceptionHandler.ErrorResponse) response.getEntity();
        assertThat(body.path).isNull();
        assertThat(body.code).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void mapsNistExceptionToServiceUnavailable()
    {
        Response response = handler.toResponse(new NistException("NIST service down"));

        assertThat(response.getStatus()).isEqualTo(Response.Status.SERVICE_UNAVAILABLE.getStatusCode());
        GlobalExceptionHandler.ErrorResponse body = (GlobalExceptionHandler.ErrorResponse) response.getEntity();
        assertThat(body.code).isEqualTo("NIST_SERVICE_ERROR");
        assertThat(body.message).contains("NIST service down");
    }

    @Test
    void mapsNotFoundTo404()
    {
        Response response = handler.toResponse(new NotFoundException("missing"));

        assertThat(response.getStatus()).isEqualTo(Response.Status.NOT_FOUND.getStatusCode());
        GlobalExceptionHandler.ErrorResponse body = (GlobalExceptionHandler.ErrorResponse) response.getEntity();
        assertThat(body.code).isEqualTo("NOT_FOUND");
        assertThat(body.message).contains("missing");
    }

    @Test
    void mapsUnhandledTo500()
    {
        Response response = handler.toResponse(new RuntimeException("boom"));

        assertThat(response.getStatus()).isEqualTo(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
        GlobalExceptionHandler.ErrorResponse body = (GlobalExceptionHandler.ErrorResponse) response.getEntity();
        assertThat(body.code).isEqualTo("INTERNAL_ERROR");
        assertThat(body.message).isEqualTo("An unexpected error occurred");
    }
}
