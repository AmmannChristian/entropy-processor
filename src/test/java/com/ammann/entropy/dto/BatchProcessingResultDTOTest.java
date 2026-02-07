package com.ammann.entropy.dto;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class BatchProcessingResultDTOTest
{

    @ParameterizedTest
    @MethodSource("resultVariants")
    void factoryMethodsPopulateFields(BatchProcessingResultDTO dto, boolean success, int received, int persisted, List<Long> missing, String msg)
    {
        assertThat(dto.success()).isEqualTo(success);
        assertThat(dto.receivedEvents()).isEqualTo(received);
        assertThat(dto.persistedEvents()).isEqualTo(persisted);
        assertThat(dto.missingSequences()).containsExactlyElementsOf(missing);
        if (msg != null) {
            assertThat(dto.errorMessage()).contains(msg);
        } else {
            assertThat(dto.errorMessage()).isNull();
        }
    }

    private static Stream<Arguments> resultVariants()
    {
        return Stream.of(
                Arguments.of(BatchProcessingResultDTO.success(1, 10, 9, 12), true, 10, 9, List.of(), null),
                Arguments.of(BatchProcessingResultDTO.failure(2, 5, "broken"), false, 5, 0, List.of(), "broken"),
                Arguments.of(BatchProcessingResultDTO.withMetrics(3, 4, 4, 7, new EdgeValidationMetricsDTO(1.0, true, true, null, null, null, null, null)),
                        true, 4, 4, List.of(), null)
        );
    }
}
