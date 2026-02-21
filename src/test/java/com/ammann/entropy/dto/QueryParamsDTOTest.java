/* (C)2026 */
package com.ammann.entropy.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ammann.entropy.enumeration.JobStatus;
import com.ammann.entropy.enumeration.TestType;
import com.ammann.entropy.enumeration.ValidationType;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class QueryParamsDTOTest {
    @Test
    @TestTransaction
    void jobQueryParams_noFilters_defaultOrder() {
        NistValidationJobQueryParamsDTO dto = new NistValidationJobQueryParamsDTO();

        // All fields null means no filters and the default ORDER BY createdAt DESC.
        var query = dto.buildQuery(null);

        assertThat(query).isNotNull();
        assertThat(query.list()).isNotNull();
    }

    @Test
    @TestTransaction
    void jobQueryParams_allFiltersSet_customSort() {
        NistValidationJobQueryParamsDTO dto = new NistValidationJobQueryParamsDTO();
        dto.status = JobStatus.COMPLETED;
        dto.validationType = ValidationType.SP_800_22;
        dto.createdBy = "test-user";
        dto.from = Instant.now().minusSeconds(3600).toString();
        dto.to = Instant.now().toString();
        dto.search = "keyword";

        SortRequestDTO sort = new SortRequestDTO();
        sort.sortFields = List.of("createdAt:desc");

        var query = dto.buildQuery(sort);

        assertThat(query).isNotNull();
        assertThat(query.list()).isNotNull();
    }

    @Test
    @TestTransaction
    void jobQueryParams_blankStrings_treatedAsNoFilter() {
        NistValidationJobQueryParamsDTO dto = new NistValidationJobQueryParamsDTO();
        dto.createdBy = "   "; // Blank value is ignored.
        dto.from = ""; // Blank value is ignored.
        dto.to = ""; // Blank value is ignored.
        dto.search = "  "; // Blank value is ignored.

        var query = dto.buildQuery(null);

        assertThat(query).isNotNull();
        assertThat(query.list()).isNotNull();
    }

    @Test
    @TestTransaction
    void jobQueryParams_invalidSortField_throws() {
        NistValidationJobQueryParamsDTO dto = new NistValidationJobQueryParamsDTO();

        SortRequestDTO sort = new SortRequestDTO();
        sort.sortFields = List.of("injectedField:asc");

        assertThatThrownBy(() -> dto.buildQuery(sort))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid sort field");
    }

    @Test
    void jobQueryParams_toString_containsFields() {
        NistValidationJobQueryParamsDTO dto = new NistValidationJobQueryParamsDTO();
        dto.status = JobStatus.RUNNING;
        dto.createdBy = "alice";

        String str = dto.toString();

        assertThat(str).contains("RUNNING").contains("alice");
    }

    @Test
    @TestTransaction
    void testResultQueryParams_noFilters_defaultOrder() {
        NistTestResultQueryParamsDTO dto = new NistTestResultQueryParamsDTO();

        var query = dto.buildQuery(null);

        assertThat(query).isNotNull();
        assertThat(query.list()).isNotNull();
    }

    @Test
    @TestTransaction
    void testResultQueryParams_allFiltersSet_customSort() {
        NistTestResultQueryParamsDTO dto = new NistTestResultQueryParamsDTO();
        dto.testSuiteRunId = UUID.randomUUID();
        dto.passed = true;
        dto.testName = "Frequency";
        dto.from = Instant.now().minusSeconds(3600).toString();
        dto.to = Instant.now().toString();
        dto.search = "freq";

        SortRequestDTO sort = new SortRequestDTO();
        sort.sortFields = List.of("executedAt:desc");

        var query = dto.buildQuery(sort);

        assertThat(query).isNotNull();
        assertThat(query.list()).isNotNull();
    }

    @Test
    @TestTransaction
    void testResultQueryParams_blankStrings_treatedAsNoFilter() {
        NistTestResultQueryParamsDTO dto = new NistTestResultQueryParamsDTO();
        dto.testName = ""; // blank
        dto.from = "  "; // blank
        dto.to = ""; // blank
        dto.search = "  "; // blank

        var query = dto.buildQuery(null);

        assertThat(query).isNotNull();
        assertThat(query.list()).isNotNull();
    }

    @Test
    @TestTransaction
    void testResultQueryParams_passedFalse_addsFilter() {
        NistTestResultQueryParamsDTO dto = new NistTestResultQueryParamsDTO();
        dto.passed = false;

        var query = dto.buildQuery(null);

        assertThat(query).isNotNull();
        assertThat(query.list()).isNotNull();
    }

    @Test
    void testResultQueryParams_toString_containsFields() {
        NistTestResultQueryParamsDTO dto = new NistTestResultQueryParamsDTO();
        UUID runId = UUID.randomUUID();
        dto.testSuiteRunId = runId;
        dto.testName = "Runs";

        String str = dto.toString();

        assertThat(str).contains(runId.toString()).contains("Runs");
    }

    @Test
    @TestTransaction
    void nist90bQueryParams_noFilters_defaultOrder() {
        Nist90BResultQueryParamsDTO dto = new Nist90BResultQueryParamsDTO();

        var query = dto.buildQuery(null);

        assertThat(query).isNotNull();
        assertThat(query.list()).isNotNull();
    }

    @Test
    @TestTransaction
    void nist90bQueryParams_allFiltersSet_customSort() {
        Nist90BResultQueryParamsDTO dto = new Nist90BResultQueryParamsDTO();
        dto.assessmentRunId = UUID.randomUUID();
        dto.passed = true;
        dto.from = Instant.now().minusSeconds(3600).toString();
        dto.to = Instant.now().toString();

        SortRequestDTO sort = new SortRequestDTO();
        sort.sortFields = List.of("executedAt:asc");

        var query = dto.buildQuery(sort);

        assertThat(query).isNotNull();
        assertThat(query.list()).isNotNull();
    }

    @Test
    @TestTransaction
    void nist90bQueryParams_blankStrings_treatedAsNoFilter() {
        Nist90BResultQueryParamsDTO dto = new Nist90BResultQueryParamsDTO();
        dto.from = ""; // blank
        dto.to = "  "; // blank

        var query = dto.buildQuery(null);

        assertThat(query).isNotNull();
        assertThat(query.list()).isNotNull();
    }

    @Test
    @TestTransaction
    void nist90bQueryParams_passedFalse_addsFilter() {
        Nist90BResultQueryParamsDTO dto = new Nist90BResultQueryParamsDTO();
        dto.passed = false;

        var query = dto.buildQuery(null);

        assertThat(query).isNotNull();
        assertThat(query.list()).isNotNull();
    }

    @Test
    void nist90bQueryParams_toString_containsFields() {
        Nist90BResultQueryParamsDTO dto = new Nist90BResultQueryParamsDTO();
        UUID runId = UUID.randomUUID();
        dto.assessmentRunId = runId;
        dto.passed = false;

        String str = dto.toString();

        assertThat(str).contains(runId.toString()).contains("false");
    }

    @Test
    void testType_fromString_null_throws() {
        assertThatThrownBy(() -> TestType.fromString(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    void testType_fromString_validIid() {
        assertThat(TestType.fromString("IID")).isEqualTo(TestType.IID);
        assertThat(TestType.fromString("iid")).isEqualTo(TestType.IID);
    }

    @Test
    void testType_fromString_validNonIid() {
        assertThat(TestType.fromString("NON_IID")).isEqualTo(TestType.NON_IID);
        assertThat(TestType.fromString("non_iid")).isEqualTo(TestType.NON_IID);
    }

    @Test
    void testType_fromString_invalidValue_throws() {
        assertThatThrownBy(() -> TestType.fromString("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid test type");
    }

    @Test
    void generateCacheKey_noParams_returnsEntityName() {
        String key = CountCacheServiceDTO.generateCacheKey("MyEntity");

        assertThat(key).isEqualTo("MyEntity");
    }

    @Test
    void generateCacheKey_oneParam_appendsKeyValue() {
        String key = CountCacheServiceDTO.generateCacheKey("MyEntity", "status", "ACTIVE");

        assertThat(key).isEqualTo("MyEntity:status=ACTIVE");
    }

    @Test
    void generateCacheKey_nullValue_skipsParam() {
        String key = CountCacheServiceDTO.generateCacheKey("MyEntity", "status", null);

        assertThat(key).isEqualTo("MyEntity");
    }

    @Test
    void generateCacheKey_multipleParams_appendsAll() {
        String key = CountCacheServiceDTO.generateCacheKey("Entity", "a", "1", "b", "2", "c", "3");

        assertThat(key).isEqualTo("Entity:a=1:b=2:c=3");
    }

    @Test
    void generateCacheKey_oddParamsCount_lastParamSkipped() {
        // Odd count: last "key" has no value, the loop's i+1 check handles this
        String key = CountCacheServiceDTO.generateCacheKey("Entity", "a", "1", "orphan");

        assertThat(key).isEqualTo("Entity:a=1");
    }

    @Test
    void generateCacheKey_mixedNullValues() {
        String key = CountCacheServiceDTO.generateCacheKey("E", "k1", "v1", "k2", null, "k3", "v3");

        assertThat(key).isEqualTo("E:k1=v1:k3=v3");
    }
}
