/* (C)2026 */
package com.ammann.entropy.dto;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ammann.entropy.exception.ValidationException;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class QueryValidatorDTOTest {

    @Test
    void acceptsValidPageRequest() {
        PageRequestDTO req = new PageRequestDTO();
        req.page = 0;
        req.size = 20;

        assertThatCode(() -> QueryValidatorDTO.validatePageRequest(req)).doesNotThrowAnyException();
    }

    @Test
    void acceptsMaximumPageSize() {
        PageRequestDTO req = new PageRequestDTO();
        req.page = 0;
        req.size = 1000;

        assertThatCode(() -> QueryValidatorDTO.validatePageRequest(req)).doesNotThrowAnyException();
    }

    @Test
    void rejectsPageSizeTooLarge() {
        PageRequestDTO req = new PageRequestDTO();
        req.page = 0;
        req.size = 1001;

        assertThatThrownBy(() -> QueryValidatorDTO.validatePageRequest(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("size")
                .hasMessageContaining("1001")
                .hasMessageContaining("maximum 1000");
    }

    @Test
    void rejectsDeepPagination() {
        PageRequestDTO req = new PageRequestDTO();
        req.page = 101; // offset = 101 * 1000 = 101,000
        req.size = 1000;

        assertThatThrownBy(() -> QueryValidatorDTO.validatePageRequest(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("page")
                .hasMessageContaining("101")
                .hasMessageContaining("offset too large");
    }

    @Test
    void acceptsPageAtSafeOffsetBoundary() {
        PageRequestDTO req = new PageRequestDTO();
        req.page = 100; // offset = 100 * 1000 = 100,000 (exactly at limit)
        req.size = 1000;

        assertThatCode(() -> QueryValidatorDTO.validatePageRequest(req)).doesNotThrowAnyException();
    }

    @Test
    void rejectsPageJustOverSafeOffsetBoundary() {
        PageRequestDTO req = new PageRequestDTO();
        req.page = 5001; // offset = 5001 * 20 = 100,020 (just over 100,000 limit)
        req.size = 20;

        assertThatThrownBy(() -> QueryValidatorDTO.validatePageRequest(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("offset too large");
    }
}
