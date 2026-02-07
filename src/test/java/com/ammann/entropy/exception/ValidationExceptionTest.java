package com.ammann.entropy.exception;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationExceptionTest
{

    @ParameterizedTest
    @MethodSource("insufficientDataSamples")
    void buildsInsufficientDataMessages(String resource, int required, int actual, String expected)
    {
        ValidationException ex = ValidationException.insufficientData(resource, required, actual);
        assertThat(ex.getMessage()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "alpha,-1,> 0",
            "window,null,non-null"
    })
    void buildsInvalidParameterMessage(String param, String value, String expectedFragment)
    {
        ValidationException ex = ValidationException.invalidParameter(param, value, expectedFragment);
        assertThat(ex.getMessage()).contains(param, value, expectedFragment);
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> insufficientDataSamples()
    {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("entropy bits", 1_000_000, 10,
                        "Insufficient entropy bits: need at least 1000000, but got 10"),
                org.junit.jupiter.params.provider.Arguments.of("samples", 5, 2,
                        "Insufficient samples: need at least 5, but got 2")
        );
    }
}
