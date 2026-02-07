package com.ammann.entropy.exception;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;

/**
 * Global JAX-RS exception mapper that translates application and framework exceptions
 * into structured JSON error responses with appropriate HTTP status codes.
 *
 * <p>Handles authentication, authorization, validation, NIST service, and generic
 * internal errors. Unhandled exceptions are logged at ERROR level and returned as
 * HTTP 500 responses.
 */
@Provider
public class GlobalExceptionHandler implements ExceptionMapper<Exception>
{
    private static final Logger LOG = Logger.getLogger(GlobalExceptionHandler.class);

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(Exception exception)
    {
        String path = uriInfo != null ? uriInfo.getPath() : null;

        // Authentication exceptions
        if (exception instanceof UnauthorizedException || exception instanceof AuthenticationFailedException) {
            LOG.debugf("Authentication failed for path %s: %s", path, exception.getMessage());
            return createResponse(
                    Response.Status.UNAUTHORIZED,
                    "Authentication required",
                    "UNAUTHORIZED",
                    path
            );
        }

        // Authorization exceptions
        if (exception instanceof ForbiddenException) {
            LOG.warnf("Access denied for path %s: %s", path, exception.getMessage());
            return createResponse(
                    Response.Status.FORBIDDEN,
                    "Insufficient permissions",
                    "FORBIDDEN",
                    path
            );
        }

        if (exception instanceof ValidationException) {
            return createResponse(
                    Response.Status.BAD_REQUEST,
                    exception.getMessage(),
                    "VALIDATION_ERROR",
                    path
            );
        }

        if (exception instanceof NistException) {
            LOG.warnf("NIST service error: %s", exception.getMessage());
            return createResponse(
                    Response.Status.SERVICE_UNAVAILABLE,
                    exception.getMessage(),
                    "NIST_SERVICE_ERROR",
                    path
            );
        }

        if (exception instanceof NotFoundException) {
            return createResponse(
                    Response.Status.NOT_FOUND,
                    exception.getMessage(),
                    "NOT_FOUND",
                    path
            );
        }

        if (exception instanceof SomeThingWentWrongException) {
            return createResponse(
                    Response.Status.INTERNAL_SERVER_ERROR,
                    exception.getMessage(),
                    "INTERNAL_ERROR",
                    path
            );
        }

        LOG.error("Unhandled exception: " + exception.getClass().getSimpleName(), exception);
        return createResponse(
                Response.Status.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred",
                "INTERNAL_ERROR",
                path
        );
    }

    private Response createResponse(Response.Status status, String message, String code, String path)
    {
        ErrorResponse errorResponse = new ErrorResponse(code, message, path, status.getStatusCode());
        return Response.status(status).entity(errorResponse).build();
    }


    /**
     * Structured error response body returned to API clients.
     */
    public static class ErrorResponse
    {
        public String code;
        public String message;
        public LocalDateTime timestamp;
        public String path;
        public Integer status;

        public ErrorResponse(String code, String message)
        {
            this.code = code;
            this.message = message;
            this.timestamp = LocalDateTime.now();
        }

        public ErrorResponse(String code, String message, String path, Integer status)
        {
            this(code, message);
            this.path = path;
            this.status = status;
        }
    }
}
