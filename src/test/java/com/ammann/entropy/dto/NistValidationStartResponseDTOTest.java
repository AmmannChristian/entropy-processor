/* (C)2026 */
package com.ammann.entropy.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class NistValidationStartResponseDTOTest {

    @Test
    void queuedCreatesResponseWithCorrectStatus() {
        UUID jobId = UUID.randomUUID();

        NistValidationStartResponseDTO response =
                NistValidationStartResponseDTO.queued(jobId, "SP_800_22");

        assertThat(response.jobId()).isEqualTo(jobId);
        assertThat(response.status()).isEqualTo("QUEUED");
    }

    @Test
    void queuedMessageIncludesValidationTypeAndJobId() {
        UUID jobId = UUID.randomUUID();

        NistValidationStartResponseDTO response =
                NistValidationStartResponseDTO.queued(jobId, "SP_800_22");

        assertThat(response.message()).contains("SP_800_22");
        assertThat(response.message()).contains(jobId.toString());
    }

    @Test
    void queuedMessageIncludesPollingEndpoint() {
        UUID jobId = UUID.randomUUID();

        NistValidationStartResponseDTO response =
                NistValidationStartResponseDTO.queued(jobId, "SP_800_90B");

        assertThat(response.message()).contains("GET /api/v1/entropy/nist/validate/status/");
        assertThat(response.message()).contains("check progress");
    }

    @Test
    void queuedHandlesNullValidationType() {
        UUID jobId = UUID.randomUUID();

        NistValidationStartResponseDTO response =
                NistValidationStartResponseDTO.queued(jobId, null);

        assertThat(response.status()).isEqualTo("QUEUED");
        assertThat(response.message()).contains("null");
    }
}
