/* (C)2026 */
package com.ammann.entropy.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ammann.entropy.dto.DataQualityReportDTO;
import com.ammann.entropy.dto.EventCountResponseDTO;
import com.ammann.entropy.dto.EventRateResponseDTO;
import com.ammann.entropy.dto.IntervalStatisticsDTO;
import com.ammann.entropy.dto.RecentEventsResponseDTO;
import com.ammann.entropy.exception.ValidationException;
import com.ammann.entropy.model.EntropyData;
import com.ammann.entropy.service.DataQualityService;
import com.ammann.entropy.support.TestDataFactory;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class EventsResourceTest {

    @Test
    @TestTransaction
    void getRecentEventsReturnsSummaries() {
        EntropyData.deleteAll();
        EventsResource resource = buildResource();

        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        EntropyData e1 = TestDataFactory.createEntropyEvent(1, 1_000L, base);
        EntropyData e2 = TestDataFactory.createEntropyEvent(2, 2_000L, base.plusSeconds(1));
        EntropyData.persist(e1, e2);

        var response = resource.getRecentEvents(2);
        RecentEventsResponseDTO dto = (RecentEventsResponseDTO) response.getEntity();

        assertThat(dto.count()).isEqualTo(2);
        assertThat(dto.events()).hasSize(2);
        assertThat(dto.events().get(1).intervalToPreviousNs()).isEqualTo(1_000L);
    }

    @Test
    @TestTransaction
    void getRecentEventsReturnsEmptyWhenNoData() {
        EntropyData.deleteAll();
        EventsResource resource = buildResource();

        var response = resource.getRecentEvents(5);
        RecentEventsResponseDTO dto = (RecentEventsResponseDTO) response.getEntity();

        assertThat(dto.count()).isZero();
        assertThat(dto.events()).isEmpty();
        assertThat(dto.oldestEvent()).isNull();
        assertThat(dto.newestEvent()).isNull();
    }

    @Test
    void getRecentEventsRejectsInvalidCount() {
        EventsResource resource = buildResource();

        assertThatThrownBy(() -> resource.getRecentEvents(0))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> resource.getRecentEvents(10001))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @TestTransaction
    void getEventCountReturnsCount() {
        EntropyData.deleteAll();
        EventsResource resource = buildResource();

        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = start.plusSeconds(10);

        EntropyData.persist(
                TestDataFactory.createEntropyEvent(1, 1_000L, start.plusSeconds(1)),
                TestDataFactory.createEntropyEvent(2, 2_000L, start.plusSeconds(2)));

        var response = resource.getEventCount(start.toString(), end.toString());
        EventCountResponseDTO dto = (EventCountResponseDTO) response.getEntity();

        assertThat(dto.count()).isEqualTo(2L);
        assertThat(dto.durationSeconds()).isEqualTo(10L);
    }

    @Test
    @TestTransaction
    void getStatisticsReturnsDto() {
        EntropyData.deleteAll();
        EventsResource resource = buildResource();

        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = start.plusSeconds(10);

        EntropyData.persist(
                TestDataFactory.createEntropyEvent(1, 1_000L, start.plusSeconds(1)),
                TestDataFactory.createEntropyEvent(2, 2_000L, start.plusSeconds(2)),
                TestDataFactory.createEntropyEvent(3, 3_500L, start.plusSeconds(3)));

        var response = resource.getStatistics(start.toString(), end.toString());
        IntervalStatisticsDTO dto = (IntervalStatisticsDTO) response.getEntity();

        assertThat(dto.count()).isEqualTo(2L);
        assertThat(dto.meanNs()).isGreaterThan(0.0);
    }

    @Test
    @TestTransaction
    void getStatisticsThrowsWhenNoIntervals() {
        EntropyData.deleteAll();
        EventsResource resource = buildResource();

        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = start.plusSeconds(10);

        EntropyData.persist(TestDataFactory.createEntropyEvent(1, 1_000L, start.plusSeconds(1)));

        assertThatThrownBy(() -> resource.getStatistics(start.toString(), end.toString()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @TestTransaction
    void getIntervalStatisticsReturnsDto() {
        EntropyData.deleteAll();
        EventsResource resource = buildResource();

        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = start.plusSeconds(10);

        EntropyData.persist(
                TestDataFactory.createEntropyEvent(1, 1_000L, start.plusSeconds(1)),
                TestDataFactory.createEntropyEvent(2, 3_000L, start.plusSeconds(2)));

        var response = resource.getIntervalStatistics(start.toString(), end.toString());
        IntervalStatisticsDTO dto = (IntervalStatisticsDTO) response.getEntity();

        assertThat(dto.count()).isEqualTo(1L);
        assertThat(dto.minNs()).isEqualTo(2_000L);
    }

    @Test
    @TestTransaction
    void getIntervalStatisticsThrowsWhenNoIntervals() {
        EntropyData.deleteAll();
        EventsResource resource = buildResource();

        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = start.plusSeconds(10);

        EntropyData.persist(TestDataFactory.createEntropyEvent(1, 1_000L, start.plusSeconds(1)));

        assertThatThrownBy(() -> resource.getIntervalStatistics(start.toString(), end.toString()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @TestTransaction
    void getQualityReportReturnsReport() {
        EntropyData.deleteAll();
        EventsResource resource = buildResource();

        Instant base = Instant.now().minusSeconds(30);
        List<EntropyData> events = TestDataFactory.buildSequentialEvents(10, 1_000L, base);
        EntropyData.persist(events);

        var response = resource.getQualityReport(null, null);
        DataQualityReportDTO dto = (DataQualityReportDTO) response.getEntity();

        assertThat(dto).isNotNull();
        assertThat(dto.totalEvents()).isEqualTo(10L);
    }

    @Test
    @TestTransaction
    void getQualityReportThrowsWhenNoEvents() {
        EntropyData.deleteAll();
        EventsResource resource = buildResource();

        assertThatThrownBy(() -> resource.getQualityReport(null, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @TestTransaction
    void getQualityReportThrowsWhenServiceReturnsNull() {
        EntropyData.deleteAll();
        EventsResource resource = new EventsResource();
        resource.expectedRateHz = 184.0;
        resource.dataQualityService =
                new DataQualityService() {
                    @Override
                    public DataQualityReportDTO assessDataQuality(List<EntropyData> events) {
                        return null;
                    }
                };

        Instant base = Instant.now().minusSeconds(30);
        List<EntropyData> events = TestDataFactory.buildSequentialEvents(10, 1_000L, base);
        EntropyData.persist(events);

        assertThatThrownBy(() -> resource.getQualityReport(null, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @TestTransaction
    void getEventRateReturnsRate() {
        EntropyData.deleteAll();
        EventsResource resource = buildResource();

        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = start.plusSeconds(10);

        EntropyData.persist(
                TestDataFactory.createEntropyEvent(1, 1_000L, start.plusSeconds(1)),
                TestDataFactory.createEntropyEvent(2, 2_000L, start.plusSeconds(2)));

        var response = resource.getEventRate(start.toString(), end.toString());
        EventRateResponseDTO dto = (EventRateResponseDTO) response.getEntity();

        assertThat(dto.totalEvents()).isEqualTo(2L);
        assertThat(dto.averageRateHz()).isGreaterThan(0.0);
    }

    @Test
    void getEventRateRejectsInvalidWindow() {
        EventsResource resource = buildResource();

        assertThatThrownBy(
                        () -> resource.getEventRate("2024-01-02T00:00:00Z", "2024-01-01T00:00:00Z"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> resource.getEventRate("bad", "2024-01-01T00:00:00Z"))
                .isInstanceOf(ValidationException.class);
    }

    private EventsResource buildResource() {
        EventsResource resource = new EventsResource();
        resource.dataQualityService = new DataQualityService();
        resource.expectedRateHz = 184.0;
        return resource;
    }
}
