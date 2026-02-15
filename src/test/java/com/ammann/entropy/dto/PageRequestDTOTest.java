/* (C)2026 */
package com.ammann.entropy.dto;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PageRequestDTOTest {

    @Test
    void calculatesOffsetCorrectly() {
        PageRequestDTO req = new PageRequestDTO();
        req.page = 0;
        req.size = 20;

        assertThat(req.getOffset()).isEqualTo(0);
    }

    @Test
    void calculatesOffsetForSecondPage() {
        PageRequestDTO req = new PageRequestDTO();
        req.page = 1;
        req.size = 20;

        assertThat(req.getOffset()).isEqualTo(20);
    }

    @Test
    void calculatesOffsetForArbitraryPage() {
        PageRequestDTO req = new PageRequestDTO();
        req.page = 5;
        req.size = 50;

        assertThat(req.getOffset()).isEqualTo(250);
    }

    @Test
    void getLimitReturnsSize() {
        PageRequestDTO req = new PageRequestDTO();
        req.size = 42;

        assertThat(req.getLimit()).isEqualTo(42);
    }

    @Test
    void fromCountCreatesPageRequestWithPageZero() {
        PageRequestDTO req = PageRequestDTO.fromCount(100);

        assertThat(req.page).isEqualTo(0);
        assertThat(req.size).isEqualTo(100);
    }

    @Test
    void fromCountCapsAtMaximumSize() {
        PageRequestDTO req = PageRequestDTO.fromCount(5000);

        assertThat(req.page).isEqualTo(0);
        assertThat(req.size).isEqualTo(1000);
    }

    @Test
    void fromCountPreservesSmallValues() {
        PageRequestDTO req = PageRequestDTO.fromCount(50);

        assertThat(req.page).isEqualTo(0);
        assertThat(req.size).isEqualTo(50);
    }
}
