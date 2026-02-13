/* (C)2026 */
package com.ammann.entropy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ammann.entropy.dto.NISTSuiteResultDTO;
import com.ammann.entropy.exception.NistException;
import com.ammann.entropy.grpc.proto.sp80022.Sp80022TestResponse;
import com.ammann.entropy.grpc.proto.sp80022.Sp80022TestResult;
import com.ammann.entropy.grpc.proto.sp80022.Sp80022TestService;
import com.ammann.entropy.grpc.proto.sp80090b.Sp80090bAssessmentResponse;
import com.ammann.entropy.grpc.proto.sp80090b.Sp80090bAssessmentService;
import com.ammann.entropy.grpc.proto.sp80090b.Sp80090bEstimatorResult;
import com.ammann.entropy.model.EntropyData;
import com.ammann.entropy.model.Nist90BResult;
import com.ammann.entropy.model.NistTestResult;
import com.ammann.entropy.support.TestDataFactory;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class NistValidationServiceTest {

    @Inject NistValidationService service;

    @AfterEach
    void resetOverrides() {
        service.setClientOverride(null);
        service.setSp80090bOverride(null);
        service.setSp80022MaxBytesForTesting(1_250_000);
        service.setSp80022MinBitsForTesting(1_000_000L);
        service.setSp80090bMaxBytesForTesting(1_000_000);
    }

    @Test
    @TestTransaction
    void validateTimeWindowPersistsOnSuccess() {
        seedEntropyEvents("batch-success");
        service.setClientOverride(sp80022Success());
        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now();

        NISTSuiteResultDTO dto = service.validateTimeWindow(start, end);

        assertThat(dto.totalTests()).isEqualTo(2);
        assertThat(dto.passedTests()).isEqualTo(2);
        assertThat(NistTestResult.count()).isEqualTo(2L);
        assertThat(Nist90BResult.count()).isZero();
    }

    @Test
    @TestTransaction
    void validateTimeWindowThrowsWhenNoData() {
        EntropyData.deleteAll();

        Instant start = Instant.now().minusSeconds(30);
        Instant end = Instant.now();

        assertThatThrownBy(() -> service.validateTimeWindow(start, end))
                .isInstanceOf(NistException.class)
                .hasMessageContaining("No entropy data");
    }

    @Test
    @TestTransaction
    void validateTimeWindowThrowsWhenNotEnoughBits() {
        EntropyData.deleteAll();
        EntropyData event =
                TestDataFactory.createEntropyEvent(1, 1_000L, Instant.now().minusSeconds(5));
        event.whitenedEntropy = new byte[] {1};
        event.persist();

        Instant start = Instant.now().minusSeconds(10);
        Instant end = Instant.now();

        assertThatThrownBy(() -> service.validateTimeWindow(start, end))
                .isInstanceOf(NistException.class)
                .hasMessageContaining("Need at least");
    }

    @Test
    @TestTransaction
    void validateTimeWindowThrowsWhenSp80022Unavailable() {
        seedEntropyEvents("batch-unavailable");
        service.setClientOverride(sp80022Failure(Status.UNAVAILABLE));

        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now();

        assertThatThrownBy(() -> service.validateTimeWindow(start, end))
                .isInstanceOf(NistException.class)
                .hasMessageContaining("NIST service unavailable");
    }

    @Test
    @TestTransaction
    void validateTimeWindowThrowsWhenSp80022GrpcFails() {
        seedEntropyEvents("batch-failed");
        service.setClientOverride(sp80022Failure(Status.INTERNAL));

        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now();

        assertThatThrownBy(() -> service.validateTimeWindow(start, end))
                .isInstanceOf(NistException.class)
                .hasMessageContaining("NIST gRPC call failed");
    }

    @Test
    @TestTransaction
    void validateTimeWindowDoesNotCallSp80090b() {
        seedEntropyEvents("batch-90b-not-used");
        service.setClientOverride(sp80022Success());
        service.setSp80090bOverride(sp80090bFailure(Status.UNAVAILABLE));

        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now();

        NISTSuiteResultDTO dto = service.validateTimeWindow(start, end);

        assertThat(dto.totalTests()).isEqualTo(2);
        assertThat(Nist90BResult.count()).isZero();
    }

    @Test
    @TestTransaction
    void validateTimeWindowChunksAndAggregatesResults() {
        seedEntropyEvents("batch-chunked");
        service.setClientOverride(sp80022Success());
        service.setSp80022MinBitsForTesting(8_000L);
        service.setSp80022MaxBytesForTesting(30_000);

        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now();

        NISTSuiteResultDTO dto = service.validateTimeWindow(start, end);

        assertThat(dto.totalTests()).isEqualTo(10);
        assertThat(dto.passedTests()).isEqualTo(10);
        assertThat(dto.failedTests()).isZero();
        assertThat(NistTestResult.count()).isEqualTo(10L);
        List<NistTestResult> persisted = NistTestResult.findAll().list();
        assertThat(persisted)
                .extracting(result -> result.chunkCount)
                .containsOnly(5);
        assertThat(persisted)
                .extracting(result -> result.chunkIndex)
                .contains(1, 2, 3, 4, 5);
        assertThat(persisted.stream().map(result -> result.testSuiteRunId).distinct().count()).isEqualTo(1);
    }

    @Test
    @TestTransaction
    void validate90BTimeWindowThrowsWhenSp80090bUnavailable() {
        seedEntropyEvents("batch-90b-unavailable");
        service.setSp80090bOverride(sp80090bFailure(Status.UNAVAILABLE));

        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now();

        assertThatThrownBy(() -> service.validate90BTimeWindow(start, end))
                .isInstanceOf(NistException.class)
                .hasMessageContaining("NIST SP 800-90B service unavailable");
    }

    @Test
    @TestTransaction
    void validate90BTimeWindowThrowsWhenSp80090bClientMissing() throws Exception {
        seedEntropyEvents("batch-90b-missing");
        service.setSp80090bOverride(null);

        Object original = setSp80090bClient(null);
        try {
            Instant start = Instant.now().minusSeconds(60);
            Instant end = Instant.now();

            assertThatThrownBy(() -> service.validate90BTimeWindow(start, end))
                    .isInstanceOf(NistException.class)
                    .hasMessageContaining("NIST SP 800-90B client not available");
        } finally {
            setSp80090bClient(original);
        }
    }

    @Test
    @TestTransaction
    void getLatestValidationResultBuildsDto() {
        NistTestResult.deleteAll();
        UUID runId = UUID.randomUUID();
        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now();

        NistTestResult result = new NistTestResult(runId, "Frequency", true, 0.9, start, end);
        result.dataSampleSize = 1024L;
        result.persist();

        NISTSuiteResultDTO dto = service.getLatestValidationResult();

        assertThat(dto).isNotNull();
        assertThat(dto.totalTests()).isEqualTo(1);
        assertThat(dto.passedTests()).isEqualTo(1);
        assertThat(dto.datasetSize()).isEqualTo(1024L);
    }

    @Test
    void extractWhitenedBitsFallsBackToIntervals() throws Exception {
        EntropyData a = new EntropyData("t1", 1_000L, 1L);
        EntropyData b = new EntropyData("t2", 2_500L, 2L);
        EntropyData c = new EntropyData("t3", 4_000L, 3L);

        byte[] bytes = invokeExtractWhitenedBits(List.of(a, b, c));

        assertThat(bytes.length).isEqualTo(8);
    }

    @Test
    void extractWhitenedBitsUsesWhitenedEntropyWhenPresent() throws Exception {
        EntropyData a = new EntropyData("t1", 1_000L, 1L);
        a.whitenedEntropy = new byte[] {1, 2, 3};
        EntropyData b = new EntropyData("t2", 2_000L, 2L);
        b.whitenedEntropy = new byte[] {4};

        byte[] bytes = invokeExtractWhitenedBits(List.of(a, b));

        assertThat(bytes).containsExactly(1, 2, 3, 4);
    }

    @Test
    void resolveTokenReturnsBearerTokenWhenPresent() throws Exception {
        OidcClientService oidcClientService = org.mockito.Mockito.mock(OidcClientService.class);
        NistValidationService localService = new NistValidationService(null, null, oidcClientService);

        String token = invokeResolveToken(localService, "bearer-123", "NIST SP 800-22");

        assertThat(token).isEqualTo("bearer-123");
        verifyNoInteractions(oidcClientService);
    }

    @Test
    void resolveTokenUsesOidcClientServiceWhenConfigured() throws Exception {
        OidcClientService oidcClientService = org.mockito.Mockito.mock(OidcClientService.class);
        NistValidationService localService = new NistValidationService(null, null, oidcClientService);
        when(oidcClientService.isConfigured()).thenReturn(true);
        when(oidcClientService.getAccessTokenOrThrow()).thenReturn("svc-token");

        String token = invokeResolveToken(localService, null, "NIST SP 800-22");

        assertThat(token).isEqualTo("svc-token");
        verify(oidcClientService).getAccessTokenOrThrow();
    }

    @Test
    void resolveTokenReturnsNullWhenNotConfigured() throws Exception {
        OidcClientService oidcClientService = org.mockito.Mockito.mock(OidcClientService.class);
        NistValidationService localService = new NistValidationService(null, null, oidcClientService);
        when(oidcClientService.isConfigured()).thenReturn(false);

        String token = invokeResolveToken(localService, null, "NIST SP 800-22");

        assertThat(token).isNull();
        verify(oidcClientService).isConfigured();
    }

    @Test
    void resolveTokenThrowsWhenTokenFetchFails() throws Exception {
        OidcClientService oidcClientService = org.mockito.Mockito.mock(OidcClientService.class);
        NistValidationService localService = new NistValidationService(null, null, oidcClientService);
        when(oidcClientService.isConfigured()).thenReturn(true);
        when(oidcClientService.getAccessTokenOrThrow())
                .thenThrow(new OidcClientService.TokenFetchException("boom"));

        assertThatThrownBy(() -> invokeResolveToken(localService, null, "NIST SP 800-22"))
                .isInstanceOf(NistException.class)
                .hasMessageContaining("Authentication required but token unavailable");
    }

    @Test
    void splitSp80022ChunksCoversFullInputWithoutLoss() throws Exception {
        service.setSp80022MaxBytesForTesting(40);
        service.setSp80022MinBitsForTesting(64L);
        byte[] input = new byte[95];

        List<byte[]> chunks = invokeSplitSp80022Chunks(input);

        assertThat(chunks).hasSize(3);
        assertThat(chunks).extracting(chunk -> chunk.length).containsExactly(40, 40, 15);
        int totalBytes = chunks.stream().mapToInt(chunk -> chunk.length).sum();
        assertThat(totalBytes).isEqualTo(input.length);
    }

    @Test
    void ensureJsonDocumentWrapsPlainTextInFallbackObject() throws Exception {
        String json = invokeEnsureJsonDocument(service, "plain warning", "warning");

        assertThat(json).isEqualTo("{\"warning\":\"plain warning\"}");
    }

    @Test
    void ensureJsonDocumentPreservesValidJson() throws Exception {
        String json = invokeEnsureJsonDocument(service, "{\"warning\":\"already-json\"}", "warning");

        assertThat(json).isEqualTo("{\"warning\":\"already-json\"}");
    }

    @Test
    void ensureJsonDocumentReturnsNullForBlankInput() throws Exception {
        String json = invokeEnsureJsonDocument(service, "   ", "warning");

        assertThat(json).isNull();
    }

    private byte[] invokeExtractWhitenedBits(List<EntropyData> events) throws Exception {
        var method =
                NistValidationService.class.getDeclaredMethod("extractWhitenedBits", List.class);
        method.setAccessible(true);
        return (byte[]) method.invoke(service, events);
    }

    private String invokeResolveToken(
            NistValidationService target, String bearerToken, String serviceName) throws Exception {
        Method method =
                NistValidationService.class.getDeclaredMethod("resolveToken", String.class, String.class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(target, bearerToken, serviceName);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }

    private String invokeEnsureJsonDocument(
            NistValidationService target, String rawValue, String fallbackField) throws Exception {
        Method method =
                NistValidationService.class.getDeclaredMethod("ensureJsonDocument", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(target, rawValue, fallbackField);
    }

    @SuppressWarnings("unchecked")
    private List<byte[]> invokeSplitSp80022Chunks(byte[] bitstream) throws Exception {
        Method method = NistValidationService.class.getDeclaredMethod("splitSp80022Chunks", byte[].class);
        method.setAccessible(true);
        NistValidationService target = ClientProxy.unwrap(service);
        return (List<byte[]>) method.invoke(target, bitstream);
    }

    private void seedEntropyEvents(String batchId) {
        EntropyData.deleteAll();
        NistTestResult.deleteAll();
        Nist90BResult.deleteAll();

        Instant base = Instant.now().minusSeconds(5);
        byte[] chunk = new byte[255];
        var events = TestDataFactory.buildSequentialEvents(500, 1_000L, base);
        events.forEach(
                event -> {
                    event.batchId = batchId;
                    event.whitenedEntropy = chunk;
                });
        EntropyData.persist(events);
    }

    private Sp80022TestService sp80022Success() {
        Sp80022TestResponse grpcResponse =
                Sp80022TestResponse.newBuilder()
                        .setTestsRun(2)
                        .setOverallPassRate(1.0)
                        .setNistCompliant(true)
                        .addResults(
                                Sp80022TestResult.newBuilder()
                                        .setName("Frequency")
                                        .setPassed(true)
                                        .setPValue(0.95)
                                        .build())
                        .addResults(
                                Sp80022TestResult.newBuilder()
                                        .setName("Runs")
                                        .setPassed(true)
                                        .setPValue(0.87)
                                        .build())
                        .build();

        return request -> io.smallrye.mutiny.Uni.createFrom().item(grpcResponse);
    }

    private Sp80022TestService sp80022Failure(Status status) {
        StatusRuntimeException exception = new StatusRuntimeException(status);
        return request -> io.smallrye.mutiny.Uni.createFrom().failure(exception);
    }

    private Sp80090bAssessmentService sp80090bSuccess() {
        Sp80090bAssessmentResponse sp80090bResponse =
                Sp80090bAssessmentResponse.newBuilder()
                        .setMinEntropy(7.5)
                        .setPassed(true)
                        .setAssessmentSummary("ok")
                        .addNonIidResults(
                                Sp80090bEstimatorResult.newBuilder()
                                        .setName("Shannon")
                                        .setEntropyEstimate(7.2)
                                        .build())
                        .build();

        return request -> io.smallrye.mutiny.Uni.createFrom().item(sp80090bResponse);
    }

    private Sp80090bAssessmentService sp80090bFailure(Status status) {
        StatusRuntimeException exception = new StatusRuntimeException(status);
        return request -> io.smallrye.mutiny.Uni.createFrom().failure(exception);
    }

    private Object setSp80090bClient(Object value) throws Exception {
        Field field = NistValidationService.class.getDeclaredField("sp80090bClient");
        field.setAccessible(true);
        NistValidationService target = ClientProxy.unwrap(service);
        Object original = field.get(target);
        field.set(target, value);
        return original;
    }
}
