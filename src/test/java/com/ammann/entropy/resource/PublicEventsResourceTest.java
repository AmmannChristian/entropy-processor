/* (C)2026 */
package com.ammann.entropy.resource;

import static org.assertj.core.api.Assertions.assertThat;

import com.ammann.entropy.dto.PublicActivityResponseDTO;
import com.ammann.entropy.dto.PublicActivityResponseDTO.PublicEventSummaryDTO;
import com.ammann.entropy.model.EntropyData;
import com.ammann.entropy.support.TestDataFactory;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PublicEventsResourceTest {

    @Test
    @TestTransaction
    void getRecentActivityReturnsSummaries() {
        EntropyData.deleteAll();
        PublicEventsResource resource = new PublicEventsResource();

        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        EntropyData.persist(
                TestDataFactory.createEntropyEvent(1, 1_000L, base),
                TestDataFactory.createEntropyEvent(2, 2_000L, base.plusSeconds(1)),
                TestDataFactory.createEntropyEvent(3, 3_000L, base.plusSeconds(2)));

        var response = resource.getRecentActivity(3);
        PublicActivityResponseDTO dto = (PublicActivityResponseDTO) response.getEntity();

        assertThat(dto.count()).isEqualTo(3);
        assertThat(dto.events()).hasSize(3);
        assertThat(dto.events().getFirst().sequenceNumber()).isEqualTo(1L);
        assertThat(dto.events().getLast().sequenceNumber()).isEqualTo(3L);
        assertThat(dto.latestActivity()).isEqualTo(base.plusSeconds(2));
    }

    @Test
    @TestTransaction
    void getRecentActivityEnforcesMaxLimit() {
        EntropyData.deleteAll();
        PublicEventsResource resource = new PublicEventsResource();

        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        List<EntropyData> events = TestDataFactory.buildSequentialEvents(20, 1_000L, base);
        EntropyData.persist(events);

        var response = resource.getRecentActivity(100);
        PublicActivityResponseDTO dto = (PublicActivityResponseDTO) response.getEntity();

        // Invalid public count falls back to default response size.
        assertThat(dto.count()).isEqualTo(5);
        assertThat(dto.events()).hasSize(5);
    }

    @Test
    @TestTransaction
    void getRecentActivityReturnsEmptyWhenNoData() {
        EntropyData.deleteAll();
        PublicEventsResource resource = new PublicEventsResource();

        var response = resource.getRecentActivity(5);
        PublicActivityResponseDTO dto = (PublicActivityResponseDTO) response.getEntity();

        assertThat(dto.count()).isZero();
        assertThat(dto.events()).isEmpty();
        assertThat(dto.latestActivity()).isNull();
    }

    @Test
    void publicEventSummaryContainsOnlySafeFields() {
        List<String> fieldNames = Arrays.stream(PublicEventSummaryDTO.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(fieldNames).containsExactly("id", "sequenceNumber", "serverReceived");
    }
}
