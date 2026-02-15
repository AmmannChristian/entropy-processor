/* (C)2026 */
package com.ammann.entropy.dto;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PageResponseDTOTest {

    @Test
    void calculatesMetadataForFirstPage() {
        PageRequestDTO req = new PageRequestDTO();
        req.page = 0;
        req.size = 20;

        List<String> data = List.of("item1", "item2", "item3");
        long total = 100;

        PageResponseDTO<String> response = PageResponseDTO.of(data, req, total);

        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.total()).isEqualTo(100);
        assertThat(response.totalPages()).isEqualTo(5);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.hasPrevious()).isFalse();
        assertThat(response.data()).hasSize(3);
    }

    @Test
    void calculatesMetadataForMiddlePage() {
        PageRequestDTO req = new PageRequestDTO();
        req.page = 2;
        req.size = 20;

        List<String> data = List.of("item1", "item2");
        long total = 100;

        PageResponseDTO<String> response = PageResponseDTO.of(data, req, total);

        assertThat(response.page()).isEqualTo(2);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.hasPrevious()).isTrue();
    }

    @Test
    void calculatesMetadataForLastPage() {
        PageRequestDTO req = new PageRequestDTO();
        req.page = 4;
        req.size = 20;

        List<String> data = List.of("item1", "item2");
        long total = 100;

        PageResponseDTO<String> response = PageResponseDTO.of(data, req, total);

        assertThat(response.page()).isEqualTo(4);
        assertThat(response.totalPages()).isEqualTo(5);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.hasPrevious()).isTrue();
    }

    @Test
    void handlesPartialLastPage() {
        PageRequestDTO req = new PageRequestDTO();
        req.page = 2;
        req.size = 20;

        List<String> data = List.of("item1", "item2", "item3");
        long total = 43; // 3 pages total (20 + 20 + 3)

        PageResponseDTO<String> response = PageResponseDTO.of(data, req, total);

        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.hasPrevious()).isTrue();
    }

    @Test
    void handlesSinglePage() {
        PageRequestDTO req = new PageRequestDTO();
        req.page = 0;
        req.size = 20;

        List<String> data = List.of("item1", "item2", "item3");
        long total = 3;

        PageResponseDTO<String> response = PageResponseDTO.of(data, req, total);

        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.hasPrevious()).isFalse();
    }

    @Test
    void handlesEmptyResults() {
        PageRequestDTO req = new PageRequestDTO();
        req.page = 0;
        req.size = 20;

        List<String> data = List.of();
        long total = 0;

        PageResponseDTO<String> response = PageResponseDTO.of(data, req, total);

        assertThat(response.data()).isEmpty();
        assertThat(response.total()).isEqualTo(0);
        assertThat(response.totalPages()).isEqualTo(0);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.hasPrevious()).isFalse();
    }
}
