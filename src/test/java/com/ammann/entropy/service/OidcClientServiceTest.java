/* (C)2026 */
package com.ammann.entropy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.Tokens;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.inject.Instance;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OidcClientServiceTest {
    @Test
    void returnsEmptyWhenClientMissing() {
        Instance<OidcClient> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(false);

        OidcClientService service = new OidcClientService(instance, new SimpleMeterRegistry());
        service.initMetrics();

        assertThat(service.getAccessToken()).isEmpty();
        assertThat(service.isConfigured()).isFalse();
    }

    @Test
    void returnsTokenOnSuccess() {
        Instance<OidcClient> instance = mock(Instance.class);
        OidcClient client = mock(OidcClient.class);
        Tokens tokens = mock(Tokens.class);

        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(client);
        when(tokens.getAccessToken()).thenReturn("token-123");
        when(client.getTokens()).thenReturn(Uni.createFrom().item(tokens));

        OidcClientService service = new OidcClientService(instance, new SimpleMeterRegistry());
        service.initMetrics();

        Optional<String> token = service.getAccessToken();

        assertThat(token).contains("token-123");
        assertThat(service.isConfigured()).isTrue();
    }

    @Test
    void circuitBreakerOpensAfterFailures() throws Exception {
        Instance<OidcClient> instance = mock(Instance.class);
        OidcClient client = mock(OidcClient.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(client);
        when(client.getTokens()).thenReturn(Uni.createFrom().failure(new RuntimeException("boom")));

        OidcClientService service = new OidcClientService(instance, new SimpleMeterRegistry());
        service.initMetrics();

        for (int i = 0; i < 5; i++) {
            assertThat(service.getAccessToken()).isEmpty();
        }

        reset(client);
        when(client.getTokens()).thenReturn(Uni.createFrom().item(mock(Tokens.class)));

        assertThat(service.getAccessToken()).isEmpty();
        verify(client, never()).getTokens();

        // force circuit reset window
        Field openedAt = OidcClientService.class.getDeclaredField("circuitOpenedAt");
        openedAt.setAccessible(true);
        openedAt.setLong(service, System.currentTimeMillis() - Duration.ofMinutes(2).toMillis());

        Field failures = OidcClientService.class.getDeclaredField("consecutiveFailures");
        failures.setAccessible(true);
        ((AtomicInteger) failures.get(service)).set(5);

        assertThat(service.getAccessToken()).isEmpty();
        verify(client, atLeastOnce()).getTokens();
    }

    @Test
    void tokenFetchExceptionCarriesMessageAndCause() {
        RuntimeException cause = new RuntimeException("root");
        OidcClientService.TokenFetchException ex =
                new OidcClientService.TokenFetchException("failed", cause);

        assertThat(ex.getMessage()).contains("failed");
        assertThat(ex.getCause()).isEqualTo(cause);
    }
}
