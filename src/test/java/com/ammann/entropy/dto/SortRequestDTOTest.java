/* (C)2026 */
package com.ammann.entropy.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SortRequestDTOTest {

    @Test
    void parseSingleSortField() {
        SortRequestDTO req = new SortRequestDTO();
        req.sortFields = List.of("createdAt:desc");

        List<SortRequestDTO.SortField> parsed = req.parse();

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).field()).isEqualTo("createdAt");
        assertThat(parsed.get(0).direction()).isEqualTo("desc");
    }

    @Test
    void parseMultipleSortFields() {
        SortRequestDTO req = new SortRequestDTO();
        req.sortFields = List.of("createdAt:desc", "status:asc", "id:desc");

        List<SortRequestDTO.SortField> parsed = req.parse();

        assertThat(parsed).hasSize(3);
        assertThat(parsed.get(0).field()).isEqualTo("createdAt");
        assertThat(parsed.get(0).direction()).isEqualTo("desc");
        assertThat(parsed.get(1).field()).isEqualTo("status");
        assertThat(parsed.get(1).direction()).isEqualTo("asc");
        assertThat(parsed.get(2).field()).isEqualTo("id");
        assertThat(parsed.get(2).direction()).isEqualTo("desc");
    }

    @Test
    void defaultsToAscending() {
        SortRequestDTO req = new SortRequestDTO();
        req.sortFields = List.of("name");

        List<SortRequestDTO.SortField> parsed = req.parse();

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).field()).isEqualTo("name");
        assertThat(parsed.get(0).direction()).isEqualTo("asc");
    }

    @Test
    void returnsEmptyListWhenNoSortFields() {
        SortRequestDTO req = new SortRequestDTO();
        req.sortFields = null;

        List<SortRequestDTO.SortField> parsed = req.parse();

        assertThat(parsed).isEmpty();
    }

    @Test
    void buildOrderByClauseWithSingleField() {
        SortRequestDTO req = new SortRequestDTO();
        req.sortFields = List.of("createdAt:desc");
        Set<String> allowed = Set.of("id", "createdAt", "status");

        String orderBy = req.buildOrderByClause(allowed);

        assertThat(orderBy).isEqualTo("createdAt DESC");
    }

    @Test
    void buildOrderByClauseWithMultipleFields() {
        SortRequestDTO req = new SortRequestDTO();
        req.sortFields = List.of("createdAt:desc", "status:asc");
        Set<String> allowed = Set.of("id", "createdAt", "status");

        String orderBy = req.buildOrderByClause(allowed);

        assertThat(orderBy).isEqualTo("createdAt DESC, status ASC");
    }

    @Test
    void returnsEmptyStringWhenNoSortFieldsInOrderByClause() {
        SortRequestDTO req = new SortRequestDTO();
        req.sortFields = null;
        Set<String> allowed = Set.of("id", "name");

        String orderBy = req.buildOrderByClause(allowed);

        assertThat(orderBy).isEmpty();
    }

    @Test
    void rejectsInvalidSortField() {
        SortRequestDTO req = new SortRequestDTO();
        req.sortFields = List.of("maliciousField:desc");
        Set<String> allowed = Set.of("id", "name");

        assertThatThrownBy(() -> req.buildOrderByClause(allowed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid sort field: maliciousField")
                .hasMessageContaining(
                        "Allowed:"); // Set order is not guaranteed, just check for "Allowed:"
    }

    @Test
    void preventsSQLInjectionAttempt() {
        SortRequestDTO req = new SortRequestDTO();
        req.sortFields = List.of("id; DROP TABLE users;--");
        Set<String> allowed = Set.of("id", "name");

        assertThatThrownBy(() -> req.buildOrderByClause(allowed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid sort field");
    }

    @Test
    void normalizesDirectionCaseInsensitive() {
        SortRequestDTO req = new SortRequestDTO();
        req.sortFields = List.of("id:DESC", "name:Asc", "created:dEsC");
        Set<String> allowed = Set.of("id", "name", "created");

        String orderBy = req.buildOrderByClause(allowed);

        assertThat(orderBy).isEqualTo("id DESC, name ASC, created DESC");
    }
}
