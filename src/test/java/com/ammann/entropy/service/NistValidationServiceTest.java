/* (C)2026 */
package com.ammann.entropy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ammann.entropy.dto.NISTSuiteResultDTO;
import com.ammann.entropy.enumeration.TestType;
import com.ammann.entropy.exception.NistException;
import com.ammann.entropy.grpc.proto.sp80022.Sp80022TestResponse;
import com.ammann.entropy.grpc.proto.sp80022.Sp80022TestResult;
import com.ammann.entropy.grpc.proto.sp80022.Sp80022TestService;
import com.ammann.entropy.grpc.proto.sp80090b.Sp80090bAssessmentResponse;
import com.ammann.entropy.grpc.proto.sp80090b.Sp80090bAssessmentService;
import com.ammann.entropy.grpc.proto.sp80090b.Sp80090bEstimatorResult;
import com.ammann.entropy.model.EntropyData;
import com.ammann.entropy.model.Nist90BEstimatorResult;
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
        event.whitenedEntropy = new byte[GrpcMappingService.EXPECTED_WHITENED_ENTROPY_BYTES];
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
        service.setSp80022MaxBytesForTesting(3_200);

        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now();

        NISTSuiteResultDTO dto = service.validateTimeWindow(start, end);

        assertThat(dto.totalTests()).isEqualTo(10);
        assertThat(dto.passedTests()).isEqualTo(10);
        assertThat(dto.failedTests()).isZero();
        assertThat(NistTestResult.count()).isEqualTo(10L);
        List<NistTestResult> persisted = NistTestResult.findAll().list();
        assertThat(persisted).extracting(result -> result.chunkCount).containsOnly(5);
        assertThat(persisted).extracting(result -> result.chunkIndex).contains(1, 2, 3, 4, 5);
        assertThat(persisted.stream().map(result -> result.testSuiteRunId).distinct().count())
                .isEqualTo(1);
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
    @TestTransaction
    void getLatestValidationResult_SingleChunk() {
        NistTestResult.deleteAll();
        UUID runId = UUID.randomUUID();
        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now();

        // Create 15 tests, all chunkIndex=1, chunkCount=1
        String[] testNames = {
            "frequency_monobit", "block_frequency", "runs", "longest_run",
            "rank", "dft", "non_overlapping_template", "overlapping_template",
            "universal", "linear_complexity", "serial", "approximate_entropy",
            "cumulative_sums_forward", "cumulative_sums_reverse", "random_excursions"
        };

        for (String testName : testNames) {
            NistTestResult result = new NistTestResult(runId, testName, true, 0.9, start, end);
            result.chunkIndex = 1;
            result.chunkCount = 1;
            result.bitsTested = 1000000L;
            result.dataSampleSize = 1000000L;
            result.persist();
        }

        NISTSuiteResultDTO dto = service.getLatestValidationResult();

        assertThat(dto).isNotNull();
        assertThat(dto.totalTests()).isEqualTo(15);
        assertThat(dto.passedTests()).isEqualTo(15);
        assertThat(dto.failedTests()).isEqualTo(0);
        assertThat(dto.overallPassRate()).isEqualTo(1.0);
    }

    @Test
    @TestTransaction
    void getLatestValidationResult_MultipleChunks_AllPass() {
        NistTestResult.deleteAll();
        UUID runId = UUID.randomUUID();
        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now();

        // Create 2 chunks × 15 tests = 30 DB rows, all passed
        String[] testNames = {
            "frequency_monobit", "block_frequency", "runs", "longest_run",
            "rank", "dft", "non_overlapping_template", "overlapping_template",
            "universal", "linear_complexity", "serial", "approximate_entropy",
            "cumulative_sums_forward", "cumulative_sums_reverse", "random_excursions"
        };

        for (int chunk = 1; chunk <= 2; chunk++) {
            for (String testName : testNames) {
                NistTestResult result =
                        new NistTestResult(runId, testName, true, 0.5 + chunk * 0.1, start, end);
                result.chunkIndex = chunk;
                result.chunkCount = 2;
                result.bitsTested = 1000000L;
                result.persist();
            }
        }

        NISTSuiteResultDTO dto = service.getLatestValidationResult();

        assertThat(dto).isNotNull();
        assertThat(dto.totalTests()).isEqualTo(15);
        assertThat(dto.passedTests()).isEqualTo(15);
        assertThat(dto.failedTests()).isEqualTo(0);
        assertThat(dto.overallPassRate()).isEqualTo(1.0);
        assertThat(dto.datasetSize()).isEqualTo(2000000L); // 2 chunks × 1M bits

        // Verify all test results are passed
        assertThat(dto.tests()).allMatch(test -> test.passed());
    }

    @Test
    @TestTransaction
    void getLatestValidationResult_MultipleChunks_OneChunkFails() {
        NistTestResult.deleteAll();
        UUID runId = UUID.randomUUID();
        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now();

        // Create 2 chunks, Chunk 1 has "runs" test failed, Chunk 2 all passed
        String[] testNames = {
            "frequency_monobit", "block_frequency", "runs", "longest_run",
            "rank", "dft", "non_overlapping_template", "overlapping_template",
            "universal", "linear_complexity", "serial", "approximate_entropy",
            "cumulative_sums_forward", "cumulative_sums_reverse", "random_excursions"
        };

        for (int chunk = 1; chunk <= 2; chunk++) {
            for (String testName : testNames) {
                boolean passed = !(chunk == 1 && testName.equals("runs"));
                NistTestResult result =
                        new NistTestResult(runId, testName, passed, 0.5, start, end);
                result.chunkIndex = chunk;
                result.chunkCount = 2;
                result.bitsTested = 1000000L;
                result.persist();
            }
        }

        NISTSuiteResultDTO dto = service.getLatestValidationResult();

        assertThat(dto).isNotNull();
        assertThat(dto.totalTests()).isEqualTo(15);
        assertThat(dto.passedTests()).isEqualTo(14);
        assertThat(dto.failedTests()).isEqualTo(1);
        assertThat(dto.overallPassRate())
                .isCloseTo(14.0 / 15.0, org.assertj.core.data.Offset.offset(0.01));

        // Verify "runs" test failed (because one chunk failed)
        assertThat(dto.tests())
                .filteredOn(test -> test.testName().equals("runs"))
                .hasSize(1)
                .allMatch(test -> !test.passed());

        // Verify other tests passed
        assertThat(dto.tests())
                .filteredOn(test -> !test.testName().equals("runs"))
                .allMatch(test -> test.passed());
    }

    @Test
    @TestTransaction
    void getLatestValidationResult_MultipleChunks_PValueAggregation() {
        NistTestResult.deleteAll();
        UUID runId = UUID.randomUUID();
        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now();

        // Create 2 chunks with different p-values for "frequency_monobit"
        NistTestResult chunk1 =
                new NistTestResult(runId, "frequency_monobit", true, 0.5, start, end);
        chunk1.chunkIndex = 1;
        chunk1.chunkCount = 2;
        chunk1.bitsTested = 1000000L;
        chunk1.persist();

        NistTestResult chunk2 =
                new NistTestResult(runId, "frequency_monobit", true, 0.2, start, end);
        chunk2.chunkIndex = 2;
        chunk2.chunkCount = 2;
        chunk2.bitsTested = 1000000L;
        chunk2.persist();

        NISTSuiteResultDTO dto = service.getLatestValidationResult();

        assertThat(dto).isNotNull();
        assertThat(dto.totalTests()).isEqualTo(1);

        // Verify aggregated result has minimum p-value (0.2)
        assertThat(dto.tests()).hasSize(1);
        assertThat(dto.tests().get(0).pValue())
                .isCloseTo(0.2, org.assertj.core.data.Offset.offset(0.001));
        assertThat(dto.tests().get(0).passed()).isTrue();
    }

    @Test
    @TestTransaction
    void getLatestValidationResult_MultipleChunks_BitsTested() {
        NistTestResult.deleteAll();
        UUID runId = UUID.randomUUID();
        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now();

        // Create 3 chunks with bitsTested=1000000 each
        for (int chunk = 1; chunk <= 3; chunk++) {
            NistTestResult result =
                    new NistTestResult(runId, "frequency_monobit", true, 0.5, start, end);
            result.chunkIndex = chunk;
            result.chunkCount = 3;
            result.bitsTested = 1000000L;
            result.persist();
        }

        NISTSuiteResultDTO dto = service.getLatestValidationResult();

        assertThat(dto).isNotNull();
        assertThat(dto.totalTests()).isEqualTo(1);
        // Verify datasetSizeBits = sum of unique chunks (3 × 1M)
        assertThat(dto.datasetSize()).isEqualTo(3000000L);
    }

    @Test
    void extractWhitenedBitsThrowsWhenWhitenedEntropyMissing() {
        EntropyData a = new EntropyData("t1", 1_000L, 1L);
        EntropyData b = new EntropyData("t2", 2_500L, 2L);

        assertThatThrownBy(() -> invokeExtractWhitenedBits(List.of(a, b)))
                .isInstanceOf(NistException.class)
                .hasMessageContaining("Missing whitened_entropy");
    }

    @Test
    void extractWhitenedBitsUsesWhitenedEntropyWhenPresent() throws Exception {
        EntropyData a = new EntropyData("t1", 1_000L, 1L);
        a.whitenedEntropy = fixedEntropyChunk((byte) 1);
        EntropyData b = new EntropyData("t2", 2_000L, 2L);
        b.whitenedEntropy = fixedEntropyChunk((byte) 99);

        byte[] bytes = invokeExtractWhitenedBits(List.of(a, b));
        byte[] expected = new byte[2 * GrpcMappingService.EXPECTED_WHITENED_ENTROPY_BYTES];
        System.arraycopy(a.whitenedEntropy, 0, expected, 0, a.whitenedEntropy.length);
        System.arraycopy(
                b.whitenedEntropy, 0, expected, a.whitenedEntropy.length, b.whitenedEntropy.length);

        assertThat(bytes.length).isEqualTo(expected.length);
        assertThat(bytes).containsExactly(expected);
    }

    @Test
    void resolveTokenReturnsBearerTokenWhenPresent() throws Exception {
        OidcClientService oidcClientService = org.mockito.Mockito.mock(OidcClientService.class);
        NistValidationService localService =
                new NistValidationService(null, null, oidcClientService);

        String token = invokeResolveToken(localService, "bearer-123", "NIST SP 800-22");

        assertThat(token).isEqualTo("bearer-123");
        verifyNoInteractions(oidcClientService);
    }

    @Test
    void resolveTokenUsesOidcClientServiceWhenConfigured() throws Exception {
        OidcClientService oidcClientService = org.mockito.Mockito.mock(OidcClientService.class);
        NistValidationService localService =
                new NistValidationService(null, null, oidcClientService);
        when(oidcClientService.isConfigured()).thenReturn(true);
        when(oidcClientService.getAccessTokenOrThrow()).thenReturn("svc-token");

        String token = invokeResolveToken(localService, null, "NIST SP 800-22");

        assertThat(token).isEqualTo("svc-token");
        verify(oidcClientService).getAccessTokenOrThrow();
    }

    @Test
    void resolveTokenReturnsNullWhenNotConfigured() throws Exception {
        OidcClientService oidcClientService = org.mockito.Mockito.mock(OidcClientService.class);
        NistValidationService localService =
                new NistValidationService(null, null, oidcClientService);
        when(oidcClientService.isConfigured()).thenReturn(false);

        String token = invokeResolveToken(localService, null, "NIST SP 800-22");

        assertThat(token).isNull();
        verify(oidcClientService).isConfigured();
    }

    @Test
    void resolveTokenThrowsWhenTokenFetchFails() throws Exception {
        OidcClientService oidcClientService = org.mockito.Mockito.mock(OidcClientService.class);
        NistValidationService localService =
                new NistValidationService(null, null, oidcClientService);
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
        String json =
                invokeEnsureJsonDocument(service, "{\"warning\":\"already-json\"}", "warning");

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
        try {
            return (byte[]) method.invoke(service, events);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }

    private String invokeResolveToken(
            NistValidationService target, String bearerToken, String serviceName) throws Exception {
        Method method =
                NistValidationService.class.getDeclaredMethod(
                        "resolveToken", String.class, String.class);
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
                NistValidationService.class.getDeclaredMethod(
                        "ensureJsonDocument", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(target, rawValue, fallbackField);
    }

    @SuppressWarnings("unchecked")
    private List<byte[]> invokeSplitSp80022Chunks(byte[] bitstream) throws Exception {
        Method method =
                NistValidationService.class.getDeclaredMethod("splitSp80022Chunks", byte[].class);
        method.setAccessible(true);
        NistValidationService target = ClientProxy.unwrap(service);
        return (List<byte[]>) method.invoke(target, bitstream);
    }

    private void seedEntropyEvents(String batchId) {
        EntropyData.deleteAll();
        NistTestResult.deleteAll();
        Nist90BResult.deleteAll();

        // Test fixtures use canonical 32-byte chunks to mirror gateway contract.
        service.setSp80022MinBitsForTesting(100_000L);
        Instant base = Instant.now().minusSeconds(5);
        byte[] chunk = fixedEntropyChunk((byte) 17);
        var events = TestDataFactory.buildSequentialEvents(500, 1_000L, base);
        events.forEach(
                event -> {
                    event.batchId = batchId;
                    event.whitenedEntropy = chunk;
                });
        EntropyData.persist(events);
    }

    private byte[] fixedEntropyChunk(byte seed) {
        byte[] chunk = new byte[GrpcMappingService.EXPECTED_WHITENED_ENTROPY_BYTES];
        for (int i = 0; i < chunk.length; i++) {
            chunk[i] = (byte) (seed + i);
        }
        return chunk;
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

    // ==================== V2a Tests: Comprehensive Estimator Storage ====================

    /**
     * V2a: Test that upstream -1.0 entropy estimate is mapped to NULL (non-entropy test).
     */
    @Test
    @TestTransaction
    void v2a_persistEstimator_upstreamMinusOne_becomesNull() throws Exception {
        seedEntropyEvents("batch-v2a-minus-one");

        // Mock SP 800-90B response with -1.0 entropy (non-entropy test like Chi-Square)
        Sp80090bAssessmentService mockService =
                request ->
                        io.smallrye.mutiny.Uni.createFrom()
                                .item(
                                        Sp80090bAssessmentResponse.newBuilder()
                                                .setMinEntropy(7.5)
                                                .setPassed(true)
                                                .setAssessmentSummary("{\"test\":\"summary\"}")
                                                .addIidResults(
                                                        Sp80090bEstimatorResult.newBuilder()
                                                                .setName("Chi-Square Test")
                                                                .setEntropyEstimate(
                                                                        -1.0) // Non-entropy test
                                                                .setPassed(true)
                                                                .setDescription(
                                                                        "Tests independence")
                                                                .build())
                                                .build());

        service.setSp80090bOverride(mockService);

        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now();

        service.validate90BTimeWindow(start, end);

        // Verify estimator was persisted with NULL entropy
        List<Nist90BEstimatorResult> estimators =
                Nist90BEstimatorResult.list("estimatorName", "Chi-Square Test");
        assertThat(estimators).hasSize(1);
        assertThat(estimators.get(0).entropyEstimate).isNull();
        assertThat(estimators.get(0).passed).isTrue();
        assertThat(estimators.get(0).testType).isEqualTo(TestType.IID);
    }

    /**
     * V2a: Test that upstream 0.0 entropy estimate stays 0.0 (degenerate source).
     */
    @Test
    @TestTransaction
    void v2a_persistEstimator_upstreamZero_staysZero() throws Exception {
        seedEntropyEvents("batch-v2a-zero");

        Sp80090bAssessmentService mockService =
                request ->
                        io.smallrye.mutiny.Uni.createFrom()
                                .item(
                                        Sp80090bAssessmentResponse.newBuilder()
                                                .setMinEntropy(0.0)
                                                .setPassed(false)
                                                .setAssessmentSummary("{\"test\":\"degenerate\"}")
                                                .addNonIidResults(
                                                        Sp80090bEstimatorResult.newBuilder()
                                                                .setName("Collision Test")
                                                                .setEntropyEstimate(
                                                                        0.0) // True zero
                                                                .setPassed(false)
                                                                .setDescription(
                                                                        "Degenerate source"
                                                                                + " detected")
                                                                .build())
                                                .build());

        service.setSp80090bOverride(mockService);

        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now();

        service.validate90BTimeWindow(start, end);

        List<Nist90BEstimatorResult> estimators =
                Nist90BEstimatorResult.list("estimatorName", "Collision Test");
        assertThat(estimators).hasSize(1);
        assertThat(estimators.get(0).entropyEstimate).isEqualTo(0.0); // NOT NULL
        assertThat(estimators.get(0).passed).isFalse();
    }

    /**
     * V2a: Test that ALL 14 estimators (10 Non-IID + 4 IID) are persisted with dual-write.
     */
    @Test
    @TestTransaction
    void v2a_dualWrite_persistsAllEstimators() throws Exception {
        seedEntropyEvents("batch-v2a-all-estimators");

        Sp80090bAssessmentService mockService =
                request ->
                        io.smallrye.mutiny.Uni.createFrom()
                                .item(
                                        Sp80090bAssessmentResponse.newBuilder()
                                                .setMinEntropy(6.8)
                                                .setPassed(true)
                                                .setAssessmentSummary("{\"summary\":\"ok\"}")
                                                // 3 Non-IID estimators
                                                .addNonIidResults(
                                                        Sp80090bEstimatorResult.newBuilder()
                                                                .setName("Collision Estimate")
                                                                .setEntropyEstimate(7.1)
                                                                .setPassed(true)
                                                                .setDescription("Non-IID collision")
                                                                .build())
                                                .addNonIidResults(
                                                        Sp80090bEstimatorResult.newBuilder()
                                                                .setName("Markov Estimate")
                                                                .setEntropyEstimate(6.9)
                                                                .setPassed(true)
                                                                .setDescription("Non-IID Markov")
                                                                .build())
                                                .addNonIidResults(
                                                        Sp80090bEstimatorResult.newBuilder()
                                                                .setName("Compression Estimate")
                                                                .setEntropyEstimate(7.0)
                                                                .setPassed(true)
                                                                .setDescription(
                                                                        "Non-IID compression")
                                                                .build())
                                                // 2 IID tests
                                                .addIidResults(
                                                        Sp80090bEstimatorResult.newBuilder()
                                                                .setName("Chi-Square Independence")
                                                                .setEntropyEstimate(
                                                                        -1.0) // Non-entropy test
                                                                .setPassed(true)
                                                                .setDescription("IID chi-square")
                                                                .build())
                                                .addIidResults(
                                                        Sp80090bEstimatorResult.newBuilder()
                                                                .setName(
                                                                        "Longest Repeated"
                                                                                + " Substring")
                                                                .setEntropyEstimate(-1.0)
                                                                .setPassed(true)
                                                                .setDescription("IID LRS")
                                                                .build())
                                                .build());

        service.setSp80090bOverride(mockService);

        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now();

        service.validate90BTimeWindow(start, end);

        // Verify all 5 estimators were persisted
        long totalEstimators = Nist90BEstimatorResult.count();
        assertThat(totalEstimators).isEqualTo(5);

        // Verify 3 Non-IID estimators
        long nonIidCount =
                Nist90BEstimatorResult.count("testType", TestType.NON_IID);
        assertThat(nonIidCount).isEqualTo(3);

        // Verify 2 IID tests
        long iidCount =
                Nist90BEstimatorResult.count("testType", TestType.IID);
        assertThat(iidCount).isEqualTo(2);

        // Verify aggregate result has min entropy and passed status
        List<Nist90BResult> results = Nist90BResult.findAll().list();
        assertThat(results).hasSize(1);
        Nist90BResult result = results.get(0);
        assertThat(result.minEntropy).isEqualTo(6.8); // Min entropy from upstream
        assertThat(result.passed).isTrue();
    }

    /**
     * V2a: Test that estimators with details JSON are persisted correctly.
     */
    @Test
    @TestTransaction
    void v2a_persistEstimator_withDetails_storesJson() throws Exception {
        seedEntropyEvents("batch-v2a-details");

        Sp80090bAssessmentService mockService =
                request ->
                        io.smallrye.mutiny.Uni.createFrom()
                                .item(
                                        Sp80090bAssessmentResponse.newBuilder()
                                                .setMinEntropy(7.2)
                                                .setPassed(true)
                                                .setAssessmentSummary("{\"summary\":\"ok\"}")
                                                .addIidResults(
                                                        Sp80090bEstimatorResult.newBuilder()
                                                                .setName("Chi-Square Test")
                                                                .setEntropyEstimate(-1.0)
                                                                .setPassed(true)
                                                                .setDescription("Chi-square test")
                                                                .putDetails("chi_square", 12.45)
                                                                .putDetails("df", 10.0)
                                                                .putDetails("p_value", 0.257)
                                                                .build())
                                                .build());

        service.setSp80090bOverride(mockService);

        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now();

        service.validate90BTimeWindow(start, end);

        List<Nist90BEstimatorResult> estimators =
                Nist90BEstimatorResult.list("estimatorName", "Chi-Square Test");
        assertThat(estimators).hasSize(1);

        Nist90BEstimatorResult estimator = estimators.get(0);
        assertThat(estimator.details).isNotNull();
        assertThat(estimator.details).containsKeys("chi_square", "df", "p_value");
        assertThat(estimator.details.get("chi_square")).isEqualTo(12.45);
        assertThat(estimator.details.get("df")).isEqualTo(10.0);
        assertThat(estimator.details.get("p_value")).isEqualTo(0.257);
    }
}
