package com.ammann.entropy.enumeration;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class QualityStatusTest
{

    @ParameterizedTest
    @CsvSource({
            "0.99,EXCELLENT",
            "0.90,GOOD",
            "0.75,WARNING",
            "0.40,CRITICAL"
    })
    void mapsScoresToStatuses(double score, QualityStatus expected)
    {
        assertThat(QualityStatus.fromScore(score)).isEqualTo(expected);
    }
}
