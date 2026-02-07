/* (C)2026 */
package com.ammann.entropy.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LivenessCheck")
class LivenessCheckTest {

    @Test
    @DisplayName("should return UP status")
    void shouldReturnUpStatus() {
        LivenessCheck livenessCheck = new LivenessCheck();

        HealthCheckResponse response = livenessCheck.call();

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(response.getName()).isEqualTo("alive");
    }

    @Test
    @DisplayName("should return consistent response")
    void shouldReturnConsistentResponse() {
        LivenessCheck livenessCheck = new LivenessCheck();

        HealthCheckResponse response1 = livenessCheck.call();
        HealthCheckResponse response2 = livenessCheck.call();

        assertThat(response1.getStatus()).isEqualTo(response2.getStatus());
        assertThat(response1.getName()).isEqualTo(response2.getName());
    }

    @Test
    @DisplayName("should have correct check name")
    void shouldHaveCorrectCheckName() {
        LivenessCheck livenessCheck = new LivenessCheck();

        HealthCheckResponse response = livenessCheck.call();

        assertThat(response.getName()).isEqualTo("alive");
    }
}
