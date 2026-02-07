package com.ammann.entropy.exception;

/**
 * Exception indicating a failure in communication with or processing by an external
 * NIST statistical test service (SP 800-22 or SP 800-90B).
 *
 * <p>Mapped to HTTP 503 (Service Unavailable) by {@link GlobalExceptionHandler}.
 */
public class NistException extends ApiException
{
    public NistException(String message, Throwable cause)
    {
        super(message, cause);
    }

    public NistException(String message)
    {
        super(message);
    }
}
