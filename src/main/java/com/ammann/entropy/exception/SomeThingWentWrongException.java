package com.ammann.entropy.exception;

/**
 * Generic internal error exception for unexpected failures that do not fit a more
 * specific exception category.
 *
 * <p>Mapped to HTTP 500 (Internal Server Error) by {@link GlobalExceptionHandler}.
 */
public class SomeThingWentWrongException extends ApiException
{
    public SomeThingWentWrongException(Throwable cause)
    {
        super("Some thing went wrong", cause);
    }
}
