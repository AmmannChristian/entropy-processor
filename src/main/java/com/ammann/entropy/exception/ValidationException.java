package com.ammann.entropy.exception;

/**
 * Exception indicating that a client-supplied parameter or dataset does not meet
 * the required constraints for the requested operation.
 *
 * <p>Mapped to HTTP 400 (Bad Request) by {@link GlobalExceptionHandler}.
 * Provides factory methods for common validation failure patterns.
 */
public class ValidationException extends ApiException {

    public ValidationException(String message) {
        super(message, null);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates validation exception for insufficient data.
     */
    public static ValidationException insufficientData(String resourceType, int required, int actual) {
        return new ValidationException(
                String.format("Insufficient %s: need at least %d, but got %d",
                        resourceType, required, actual));
    }

    /**
     * Creates validation exception for invalid parameter.
     */
    public static ValidationException invalidParameter(String paramName, Object value, String expected) {
        return new ValidationException(
                String.format("Invalid parameter '%s': got '%s', expected %s",
                        paramName, value, expected));
    }
}