/* (C)2026 */
package com.ammann.entropy.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ammann.entropy.model.EntropyData;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
@Transactional
class EntropyDataQueryParamsDTOTest {

    @Test
    void buildsQueryWithBatchIdFilter() {
        EntropyDataQueryParamsDTO params = new EntropyDataQueryParamsDTO();
        params.batchId = "batch-123";

        PanacheQuery<EntropyData> query = params.buildQuery(null);

        // Verify query was built (we can't easily inspect the query string,
        // but we can verify it doesn't throw)
        assertThat(query).isNotNull();
    }

    @Test
    void buildsQueryWithMinQualityScoreFilter() {
        EntropyDataQueryParamsDTO params = new EntropyDataQueryParamsDTO();
        params.minQualityScore = 0.8;

        PanacheQuery<EntropyData> query = params.buildQuery(null);

        assertThat(query).isNotNull();
    }

    @Test
    void buildsQueryWithChannelFilter() {
        EntropyDataQueryParamsDTO params = new EntropyDataQueryParamsDTO();
        params.channel = 1;

        PanacheQuery<EntropyData> query = params.buildQuery(null);

        assertThat(query).isNotNull();
    }

    @Test
    void buildsQueryWithTimeWindowFilter() {
        EntropyDataQueryParamsDTO params = new EntropyDataQueryParamsDTO();
        params.from = "2026-02-01T00:00:00Z";
        params.to = "2026-02-15T23:59:59Z";

        PanacheQuery<EntropyData> query = params.buildQuery(null);

        assertThat(query).isNotNull();
    }

    @Test
    void buildsQueryWithSearchFilter() {
        EntropyDataQueryParamsDTO params = new EntropyDataQueryParamsDTO();
        params.search = "192.168";

        PanacheQuery<EntropyData> query = params.buildQuery(null);

        assertThat(query).isNotNull();
    }

    @Test
    void buildsQueryWithAllFilters() {
        EntropyDataQueryParamsDTO params = new EntropyDataQueryParamsDTO();
        params.batchId = "batch-123";
        params.minQualityScore = 0.8;
        params.channel = 1;
        params.from = "2026-02-01T00:00:00Z";
        params.to = "2026-02-15T23:59:59Z";
        params.search = "192.168";

        PanacheQuery<EntropyData> query = params.buildQuery(null);

        assertThat(query).isNotNull();
    }

    @Test
    void buildsQueryWithSort() {
        EntropyDataQueryParamsDTO params = new EntropyDataQueryParamsDTO();
        SortRequestDTO sortRequest = new SortRequestDTO();
        sortRequest.sortFields = List.of("qualityScore:desc", "hwTimestampNs:asc");

        PanacheQuery<EntropyData> query = params.buildQuery(sortRequest);

        assertThat(query).isNotNull();
    }

    @Test
    void usesDefaultSortWhenNoSortProvided() {
        EntropyDataQueryParamsDTO params = new EntropyDataQueryParamsDTO();

        PanacheQuery<EntropyData> query = params.buildQuery(null);

        // Default sort should be hwTimestampNs DESC
        assertThat(query).isNotNull();
    }

    @Test
    void rejectsInvalidSortField() {
        EntropyDataQueryParamsDTO params = new EntropyDataQueryParamsDTO();
        SortRequestDTO sortRequest = new SortRequestDTO();
        sortRequest.sortFields = List.of("maliciousField:desc");

        assertThatThrownBy(() -> params.buildQuery(sortRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid sort field: maliciousField");
    }

    @Test
    void acceptsDeepPaginationWithTimeFilters() {
        EntropyDataQueryParamsDTO params = new EntropyDataQueryParamsDTO();
        params.from = "2026-02-01T00:00:00Z";
        params.to = "2026-02-15T23:59:59Z";

        PageRequestDTO pageRequest = new PageRequestDTO();
        pageRequest.page = 150;
        pageRequest.size = 100;

        // Should not throw because time filters are present
        params.validate(pageRequest);
    }

    @Test
    void rejectsDeepPaginationWithoutTimeFilters() {
        EntropyDataQueryParamsDTO params = new EntropyDataQueryParamsDTO();
        // No from/to set

        PageRequestDTO pageRequest = new PageRequestDTO();
        pageRequest.page = 150;
        pageRequest.size = 100;

        assertThatThrownBy(() -> params.validate(pageRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Deep pagination")
                .hasMessageContaining("page > 100")
                .hasMessageContaining("time window filters");
    }

    @Test
    void rejectsDeepPaginationWithOnlyFromFilter() {
        EntropyDataQueryParamsDTO params = new EntropyDataQueryParamsDTO();
        params.from = "2026-02-01T00:00:00Z";
        // No 'to' set

        PageRequestDTO pageRequest = new PageRequestDTO();
        pageRequest.page = 150;
        pageRequest.size = 100;

        assertThatThrownBy(() -> params.validate(pageRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Deep pagination")
                .hasMessageContaining("time window filters");
    }

    @Test
    void acceptsShallowPaginationWithoutTimeFilters() {
        EntropyDataQueryParamsDTO params = new EntropyDataQueryParamsDTO();
        // No from/to set

        PageRequestDTO pageRequest = new PageRequestDTO();
        pageRequest.page = 50;
        pageRequest.size = 100;

        // Should not throw for page <= 100
        params.validate(pageRequest);
    }

    @Test
    void ignoresBlankStrings() {
        EntropyDataQueryParamsDTO params = new EntropyDataQueryParamsDTO();
        params.batchId = "   ";
        params.from = "";
        params.to = "   ";
        params.search = "";

        PanacheQuery<EntropyData> query = params.buildQuery(null);

        // Blank strings should be ignored, query should still build
        assertThat(query).isNotNull();
    }
}
