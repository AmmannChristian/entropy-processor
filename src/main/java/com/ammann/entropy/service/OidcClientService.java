package com.ammann.entropy.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.Tokens;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service for managing OIDC client tokens for outbound service calls.
 * Provides token fetching with automatic caching, refresh, and circuit breaker pattern.
 *
 * Quarkus OIDC Client handles token caching and refresh automatically.
 * This service adds:
 * - Graceful degradation when OIDC is not configured
 * - Metrics for token operations
 * - Circuit breaker for repeated failures
 * - Centralized error handling
 */
@ApplicationScoped
public class OidcClientService {

    private static final Logger LOG = Logger.getLogger(OidcClientService.class);
    private static final Duration TOKEN_FETCH_TIMEOUT = Duration.ofSeconds(10);
    private static final int CIRCUIT_BREAKER_THRESHOLD = 5;
    private static final Duration CIRCUIT_BREAKER_RESET = Duration.ofMinutes(1);

    private final OidcClient oidcClient;
    private final MeterRegistry meterRegistry;

    private Counter tokenFetchSuccessCounter;
    private Counter tokenFetchFailureCounter;
    private Timer tokenFetchTimer;

    // Circuit breaker state
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile long circuitOpenedAt = 0;

    @Inject
    public OidcClientService(Instance<OidcClient> oidcClientInstance, MeterRegistry meterRegistry) {
        this.oidcClient = oidcClientInstance.isResolvable() ? oidcClientInstance.get() : null;
        this.meterRegistry = meterRegistry;

        if (this.oidcClient == null) {
            LOG.warn("OIDC Client not configured - outbound service calls will not include auth tokens");
        }
    }

    @PostConstruct
    void initMetrics() {
        tokenFetchSuccessCounter = Counter.builder("entropy_oidc_token_fetch_success_total")
                .description("Successful token fetches")
                .register(meterRegistry);
        tokenFetchFailureCounter = Counter.builder("entropy_oidc_token_fetch_failure_total")
                .description("Failed token fetches")
                .register(meterRegistry);
        tokenFetchTimer = Timer.builder("entropy_oidc_token_fetch_duration")
                .description("Token fetch duration")
                .register(meterRegistry);
    }

    /**
     * Gets a valid access token for outbound service calls.
     * Returns empty if OIDC is not configured or circuit is open.
     *
     * @return Optional containing the access token, or empty if unavailable
     */
    public Optional<String> getAccessToken() {
        if (oidcClient == null) {
            LOG.debug("OIDC Client not available, skipping token fetch");
            return Optional.empty();
        }

        // Check circuit breaker
        if (isCircuitOpen()) {
            LOG.warn("Circuit breaker open, skipping token fetch");
            return Optional.empty();
        }

        return tokenFetchTimer.record(this::fetchToken);
    }

    private Optional<String> fetchToken() {
        try {
            Tokens tokens = oidcClient.getTokens().await().atMost(TOKEN_FETCH_TIMEOUT);

            if (tokens == null || tokens.getAccessToken() == null) {
                recordFailure("empty_response");
                return Optional.empty();
            }

            recordSuccess();
            LOG.debug("Successfully fetched access token");
            return Optional.of(tokens.getAccessToken());

        } catch (Exception e) {
            recordFailure(e.getClass().getSimpleName());
            LOG.warnf(e, "Failed to fetch access token: %s", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Gets a valid access token or throws an exception.
     * Use this when authentication is required and failure should be propagated.
     *
     * @return the access token
     * @throws TokenFetchException if token cannot be obtained
     */
    public String getAccessTokenOrThrow() throws TokenFetchException {
        return getAccessToken()
                .orElseThrow(() -> new TokenFetchException("Failed to obtain access token"));
    }

    /**
     * Checks if the OIDC client is available and configured.
     */
    public boolean isConfigured() {
        return oidcClient != null;
    }

    private boolean isCircuitOpen() {
        if (consecutiveFailures.get() >= CIRCUIT_BREAKER_THRESHOLD) {
            long now = System.currentTimeMillis();
            if (now - circuitOpenedAt < CIRCUIT_BREAKER_RESET.toMillis()) {
                return true;
            }
            // Reset circuit breaker after timeout
            consecutiveFailures.set(0);
            circuitOpenedAt = 0;
            LOG.info("Circuit breaker reset");
        }
        return false;
    }

    private void recordSuccess() {
        consecutiveFailures.set(0);
        if (tokenFetchSuccessCounter != null) {
            tokenFetchSuccessCounter.increment();
        }
    }

    private void recordFailure(String reason) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= CIRCUIT_BREAKER_THRESHOLD && circuitOpenedAt == 0) {
            circuitOpenedAt = System.currentTimeMillis();
            LOG.errorf("Circuit breaker opened after %d consecutive failures", failures);
        }
        if (tokenFetchFailureCounter != null) {
            tokenFetchFailureCounter.increment();
        }
    }

    /**
     * Exception thrown when token fetching fails.
     */
    public static class TokenFetchException extends Exception {
        public TokenFetchException(String message) {
            super(message);
        }

        public TokenFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}