/* (C)2026 */
package com.ammann.entropy.enumeration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ValidationTypeTest {

    @Test
    void enumHasTwoValues() {
        ValidationType[] values = ValidationType.values();

        assertThat(values).hasSize(2);
        assertThat(values).contains(ValidationType.SP_800_22, ValidationType.SP_800_90B);
    }

    @Test
    void valueOfReturnsCorrectEnum() {
        assertThat(ValidationType.valueOf("SP_800_22")).isEqualTo(ValidationType.SP_800_22);
        assertThat(ValidationType.valueOf("SP_800_90B")).isEqualTo(ValidationType.SP_800_90B);
    }
}
