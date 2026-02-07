package com.ammann.entropy.exception;

/**
 * Base unchecked exception for all application-level errors in the entropy processor API.
 *
 * <p>Subclasses represent specific error categories (validation, NIST service failures,
 * internal errors) and are mapped to appropriate HTTP status codes by
 * {@link GlobalExceptionHandler}.
 */
public class ApiException extends RuntimeException
{
    public ApiException(String message, Throwable cause) {
        super(message, cause);
    }
    public ApiException(String message) {
        super(message);
    }

    public ApiException(Throwable cause) {
        super(cause);
    }
}
