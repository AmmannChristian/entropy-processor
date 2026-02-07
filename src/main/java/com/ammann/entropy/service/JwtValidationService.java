package com.ammann.entropy.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.oidc.OidcProviderClient;
import io.quarkus.oidc.TokenIntrospection;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.*;

/**
 * Service for JWT token validation with cryptographic signature verification and
 * opaque token validation via OIDC introspection.
 * Uses SmallRye JWT Parser which validates signatures against JWKS from the OIDC provider.
 * Provides graceful degradation when security is disabled.
 */
@ApplicationScoped
public class JwtValidationService {

    private static final Logger LOG = Logger.getLogger(JwtValidationService.class);
    private static final Duration INTROSPECTION_TIMEOUT = Duration.ofSeconds(10);

    private static final String ZITADEL_ROLES_CLAIM = "urn:zitadel:iam:org:project:roles";
    private static final String ZITADEL_PROJECT_ROLES_PREFIX = "urn:zitadel:iam:org:project:";
    private static final String KEYCLOAK_REALM_ACCESS = "realm_access";
    private static final String STANDARD_ROLES_CLAIM = "roles";

    @Inject
    MeterRegistry meterRegistry;

    @ConfigProperty(name = "entropy.security.enabled", defaultValue = "true")
    boolean securityEnabled;

    /**
     * JWTParser instance for cryptographic signature verification.
     * Injected as Instance to allow graceful handling when OIDC is not configured.
     * SmallRye JWT automatically fetches and caches JWKS from the configured issuer.
     */
    @Inject
    Instance<JWTParser> jwtParserInstance;

    /**
     * OIDC provider client used for token introspection (opaque tokens).
     */
    @Inject
    Instance<OidcProviderClient> oidcProviderClientInstance;

    @ConfigProperty(name = "quarkus.oidc.auth-server-url", defaultValue = "")
    String issuerUrl;

    @ConfigProperty(name = "quarkus.oidc.token.audience", defaultValue = "")
    Optional<String> expectedAudience;

    @ConfigProperty(name = "quarkus.oidc.token.allow-opaque-token-introspection", defaultValue = "false")
    boolean allowOpaqueTokenIntrospection;

    @ConfigProperty(name = "quarkus.oidc.token.allow-jwt-introspection", defaultValue = "false")
    boolean allowJwtIntrospection;

    private Counter authSuccessCounter;
    private Counter authFailureCounter;
    private Counter signatureValidationCounter;
    private Counter signatureFailureCounter;

    @PostConstruct
    void init() {
        authSuccessCounter = Counter.builder("entropy_auth_success_total")
                .description("Total successful authentications")
                .register(meterRegistry);
        authFailureCounter = Counter.builder("entropy_auth_failure_total")
                .description("Total failed authentications")
                .register(meterRegistry);
        signatureValidationCounter = Counter.builder("entropy_jwt_signature_valid_total")
                .description("Total JWT tokens with valid signatures")
                .register(meterRegistry);
        signatureFailureCounter = Counter.builder("entropy_jwt_signature_invalid_total")
                .description("Total JWT tokens with invalid signatures")
                .register(meterRegistry);

        if (jwtParserInstance.isUnsatisfied()) {
            LOG.warn("JWTParser not available - JWT signature validation will be disabled. "
                    + "Ensure quarkus-oidc or smallrye-jwt is properly configured.");
        } else {
            LOG.info("JWTParser available - JWT signature validation enabled with JWKS from: " + issuerUrl);
        }

        if (allowOpaqueTokenIntrospection && oidcProviderClientInstance.isUnsatisfied()) {
            LOG.warn("OIDC provider client not available - opaque token introspection will be disabled. "
                    + "Ensure quarkus-oidc is configured with introspection credentials.");
        }
    }

    /**
     * Validates an access token (JWT or opaque) including cryptographic signature
     * verification or introspection depending on token type.
     *
     * @param token the access token string
     * @return ValidationResult with success status and parsed claims
     */
    public ValidationResult validateToken(String token) {
        if (!this.securityEnabled) {
            LOG.debug("Security disabled, skipping token validation");
            return ValidationResult.success(null, Set.of(), null);
        }

        if (token == null || token.isBlank()) {
            recordFailure("missing_token");
            return ValidationResult.failure("Missing token");
        }

        if (!looksLikeJwt(token)) {
            if (!allowOpaqueTokenIntrospection) {
                recordFailure("opaque_introspection_disabled");
                return ValidationResult.failure("Opaque token introspection disabled");
            }
            return validateWithIntrospection(token);
        }

        // Use JWTParser for cryptographic signature validation
        if (!jwtParserInstance.isUnsatisfied()) {
            return validateWithSignature(token);
        } else if (allowJwtIntrospection) {
            return validateWithIntrospection(token);
        } else {
            // Fallback: No signature validation available - REJECT token for security
            LOG.error("JWTParser not available - rejecting token for security reasons");
            recordFailure("no_signature_validation");
            return ValidationResult.failure("Token signature validation not available");
        }
    }

    /**
     * Validates an access token asynchronously (JWT or opaque) without blocking the event loop.
     *
     * @param token the access token string
     * @return Uni with ValidationResult
     */
    public Uni<ValidationResult> validateTokenAsync(String token) {
        if (!this.securityEnabled) {
            LOG.debug("Security disabled, skipping token validation");
            return Uni.createFrom().item(ValidationResult.success(null, Set.of(), null));
        }

        if (token == null || token.isBlank()) {
            recordFailure("missing_token");
            return Uni.createFrom().item(ValidationResult.failure("Missing token"));
        }

        if (!looksLikeJwt(token)) {
            if (!allowOpaqueTokenIntrospection) {
                recordFailure("opaque_introspection_disabled");
                return Uni.createFrom().item(ValidationResult.failure("Opaque token introspection disabled"));
            }
            return validateWithIntrospectionAsync(token);
        }

        if (!jwtParserInstance.isUnsatisfied()) {
            return Uni.createFrom().item(() -> validateWithSignature(token))
                    .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
        }

        if (allowJwtIntrospection) {
            return validateWithIntrospectionAsync(token);
        }

        LOG.error("JWTParser not available - rejecting token for security reasons");
        recordFailure("no_signature_validation");
        return Uni.createFrom().item(ValidationResult.failure("Token signature validation not available"));
    }

    /**
     * Validates token with cryptographic signature verification using JWKS.
     */
    private ValidationResult validateWithSignature(String token) {
        try {
            JWTParser parser = jwtParserInstance.get();

            JsonWebToken jwt = parser.parse(token);

            // Additional issuer validation (handles URL normalization)
            String tokenIssuer = jwt.getIssuer();
            if (!isValidIssuer(tokenIssuer)) {
                recordFailure("invalid_issuer");
                signatureFailureCounter.increment();
                return ValidationResult.failure("Invalid issuer: " + tokenIssuer);
            }

            // Additional audience validation if configured
            if (expectedAudience.isPresent() && !expectedAudience.get().isBlank()) {
                if (!isValidAudience(jwt)) {
                    recordFailure("invalid_audience");
                    signatureFailureCounter.increment();
                    return ValidationResult.failure("Invalid audience");
                }
            }

            // Extract subject and roles
            String subject = jwt.getSubject();
            if (subject == null || subject.isBlank()) {
                subject = "unknown";
            }
            Set<String> roles = extractRoles(jwt);

            // Build claims JsonObject for backward compatibility
            JsonObject claims = buildClaimsJson(jwt);

            signatureValidationCounter.increment();
            recordSuccess();
            LOG.debugf("Token validated successfully (signature verified) for subject: %s with roles: %s",
                    subject, roles);

            return ValidationResult.success(subject, roles, claims);

        } catch (ParseException e) {
            LOG.warnf("JWT signature validation failed: %s", e.getMessage());
            recordFailure("signature_invalid");
            signatureFailureCounter.increment();
            return ValidationResult.failure("Token signature validation failed: " + e.getMessage());
        } catch (Exception e) {
            LOG.warn("Unexpected error during token validation", e);
            recordFailure("validation_error");
            signatureFailureCounter.increment();
            return ValidationResult.failure("Token validation failed: " + e.getMessage());
        }
    }

    /**
     * Validates opaque token using OIDC token introspection.
     */
    private ValidationResult validateWithIntrospection(String token) {
        if (oidcProviderClientInstance.isUnsatisfied()) {
            recordFailure("introspection_unavailable");
            return ValidationResult.failure("Token introspection not available");
        }

        try {
            OidcProviderClient client = oidcProviderClientInstance.get();
            TokenIntrospection introspection = client.introspectAccessToken(token)
                    .await().atMost(INTROSPECTION_TIMEOUT);

            if (introspection == null) {
                recordFailure("introspection_empty");
                return ValidationResult.failure("Token introspection failed: empty response");
            }

            if (!introspection.isActive()) {
                recordFailure("token_inactive");
                return ValidationResult.failure("Token is not active");
            }

            String subject = firstNonBlank(
                    introspection.getSubject(),
                    introspection.getUsername(),
                    introspection.getClientId(),
                    "unknown"
            );

            Set<String> roles = extractRoles(introspection);
            JsonObject claims = buildClaimsJson(introspection);

            recordSuccess();
            LOG.debugf("Token introspected successfully for subject: %s with roles: %s", subject, roles);
            return ValidationResult.success(subject, roles, claims);

        } catch (Exception e) {
            recordFailure("introspection_error");
            LOG.warnf(e, "Token introspection failed: %s", e.getMessage());
            return ValidationResult.failure("Token introspection failed: " + e.getMessage());
        }
    }

    private Uni<ValidationResult> validateWithIntrospectionAsync(String token) {
        if (oidcProviderClientInstance.isUnsatisfied()) {
            recordFailure("introspection_unavailable");
            return Uni.createFrom().item(ValidationResult.failure("Token introspection not available"));
        }

        OidcProviderClient client = oidcProviderClientInstance.get();
        return client.introspectAccessToken(token)
                .ifNoItem().after(INTROSPECTION_TIMEOUT).fail()
                .onItem().transform(introspection -> {
                    if (introspection == null) {
                        recordFailure("introspection_empty");
                        return ValidationResult.failure("Token introspection failed: empty response");
                    }

                    if (!introspection.isActive()) {
                        recordFailure("token_inactive");
                        return ValidationResult.failure("Token is not active");
                    }

                    String subject = firstNonBlank(
                            introspection.getSubject(),
                            introspection.getUsername(),
                            introspection.getClientId(),
                            "unknown"
                    );

                    Set<String> roles = extractRoles(introspection);
                    JsonObject claims = buildClaimsJson(introspection);

                    recordSuccess();
                    LOG.debugf("Token introspected successfully for subject: %s with roles: %s", subject, roles);
                    return ValidationResult.success(subject, roles, claims);
                })
                .onFailure().recoverWithItem(e -> {
                    recordFailure("introspection_error");
                    LOG.warnf(e, "Token introspection failed: %s", e.getMessage());
                    return ValidationResult.failure("Token introspection failed: " + e.getMessage());
                });
    }

    private boolean looksLikeJwt(String token) {
        int firstDot = token.indexOf('.');
        if (firstDot <= 0) {
            return false;
        }
        int secondDot = token.indexOf('.', firstDot + 1);
        if (secondDot <= firstDot + 1) {
            return false;
        }
        return token.indexOf('.', secondDot + 1) == -1;
    }

    private boolean isValidIssuer(String tokenIssuer) {
        if (issuerUrl == null || issuerUrl.isBlank()) {
            LOG.warn("No issuer configured, accepting any issuer");
            return true;
        }
        if (tokenIssuer == null) {
            return false;
        }
        // Normalize URLs for comparison (remove trailing slashes)
        String normalizedExpected = issuerUrl.replaceAll("/+$", "");
        String normalizedToken = tokenIssuer.replaceAll("/+$", "");
        return normalizedToken.equals(normalizedExpected);
    }

    private boolean isValidAudience(JsonWebToken jwt) {
        String expected = expectedAudience.orElse("");
        if (expected.isBlank()) {
            return true;
        }

        Set<String> audiences = jwt.getAudience();
        if (audiences == null || audiences.isEmpty()) {
            return false;
        }

        return audiences.contains(expected);
    }

    /**
     * Extract roles from JWT token, supporting multiple formats.
     */
    private Set<String> extractRoles(JsonWebToken jwt) {
        Set<String> roles = new HashSet<>();

        // Try Zitadel-specific role claim (generic format)
        try {
            Object zitadelRoles = jwt.getClaim(ZITADEL_ROLES_CLAIM);
            if (zitadelRoles instanceof Map<?, ?> rolesMap) {
                rolesMap.keySet().forEach(key -> {
                    if (key instanceof String) {
                        roles.add((String) key);
                    }
                });
                if (!roles.isEmpty()) {
                    LOG.debugf("Extracted %d roles from generic Zitadel claim: %s", roles.size(), roles);
                    return roles;
                }
            }
        } catch (Exception e) {
            LOG.debugf("Could not extract Zitadel roles: %s", e.getMessage());
        }

        // Try project-specific Zitadel role claims (with project ID)
        try {
            for (String claimName : jwt.getClaimNames()) {
                if (claimName.startsWith(ZITADEL_PROJECT_ROLES_PREFIX) && claimName.endsWith(":roles")) {
                    Object projectRoles = jwt.getClaim(claimName);
                    if (projectRoles instanceof Map<?, ?> rolesMap) {
                        rolesMap.keySet().forEach(key -> {
                            if (key instanceof String) {
                                roles.add((String) key);
                            }
                        });
                        if (!roles.isEmpty()) {
                            LOG.debugf("Extracted %d roles from project-specific claim '%s': %s",
                                      roles.size(), claimName, roles);
                            return roles;
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.debugf("Could not extract project-specific Zitadel roles: %s", e.getMessage());
        }

        // Try standard groups claim (used by SmallRye JWT)
        try {
            Set<String> groups = jwt.getGroups();
            if (groups != null && !groups.isEmpty()) {
                return groups;
            }
        } catch (Exception e) {
            LOG.debugf("Could not extract groups: %s", e.getMessage());
        }

        // Fallback to standard roles claim
        try {
            Object rolesClaim = jwt.getClaim(STANDARD_ROLES_CLAIM);
            if (rolesClaim instanceof Collection<?> rolesCollection) {
                rolesCollection.forEach(r -> {
                    if (r instanceof String) {
                        roles.add((String) r);
                    }
                });
                if (!roles.isEmpty()) {
                    return roles;
                }
            }
        } catch (Exception e) {
            LOG.debugf("Could not extract standard roles: %s", e.getMessage());
        }

        return roles;
    }

    /**
     * Extract roles from token introspection response (opaque tokens).
     */
    private Set<String> extractRoles(TokenIntrospection introspection) {
        Set<String> roles = new HashSet<>();
        if (introspection.getJsonObject() == null) {
            return roles;
        }

        try {
            // Try Zitadel-specific role claim (generic format)
            Object zitadelRoles = introspection.getJsonObject().get(ZITADEL_ROLES_CLAIM);
            if (zitadelRoles instanceof Map<?, ?> rolesMap) {
                rolesMap.keySet().forEach(key -> {
                    if (key instanceof String) {
                        roles.add((String) key);
                    }
                });
                if (!roles.isEmpty()) {
                    LOG.debugf("Extracted %d roles from generic Zitadel introspection claim: %s",
                            roles.size(), roles);
                    return roles;
                }
            }
        } catch (Exception e) {
            LOG.debugf("Could not extract Zitadel roles from introspection: %s", e.getMessage());
        }

        // Try project-specific Zitadel role claims (with project ID)
        try {
            for (String claimName : introspection.getJsonObject().keySet()) {
                if (claimName.startsWith(ZITADEL_PROJECT_ROLES_PREFIX) && claimName.endsWith(":roles")) {
                    Object projectRoles = introspection.getJsonObject().get(claimName);
                    if (projectRoles instanceof Map<?, ?> rolesMap) {
                        rolesMap.keySet().forEach(key -> {
                            if (key instanceof String) {
                                roles.add((String) key);
                            }
                        });
                        if (!roles.isEmpty()) {
                            LOG.debugf("Extracted %d roles from project-specific introspection claim '%s': %s",
                                    roles.size(), claimName, roles);
                            return roles;
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.debugf("Could not extract project-specific Zitadel roles from introspection: %s", e.getMessage());
        }

        // Fallback to scope claim (space-separated roles)
        try {
            String scope = introspection.getString("scope");
            if (scope != null && !scope.isBlank()) {
                for (String s : scope.split("\\s+")) {
                    if (!s.isBlank()) {
                        roles.add(s);
                    }
                }
                if (!roles.isEmpty()) {
                    return roles;
                }
            }
        } catch (Exception e) {
            LOG.debugf("Could not extract scope from introspection: %s", e.getMessage());
        }

        return roles;
    }

    /**
     * Build a JsonObject from JWT claims for backward compatibility.
     */
    private JsonObject buildClaimsJson(JsonWebToken jwt) {
        JsonObject claims = new JsonObject();

        // Standard claims
        if (jwt.getSubject() != null) {
            claims.put("sub", jwt.getSubject());
        }
        if (jwt.getIssuer() != null) {
            claims.put("iss", jwt.getIssuer());
        }
        if (jwt.getAudience() != null) {
            claims.put("aud", jwt.getAudience());
        }
        claims.put("exp", jwt.getExpirationTime());
        claims.put("iat", jwt.getIssuedAtTime());

        // Include raw token ID if present
        if (jwt.getTokenID() != null) {
            claims.put("jti", jwt.getTokenID());
        }

        return claims;
    }

    /**
     * Build a JsonObject from token introspection response.
     */
    private JsonObject buildClaimsJson(TokenIntrospection introspection) {
        if (introspection == null || introspection.getJsonObject() == null) {
            return null;
        }
        return new JsonObject(introspection.getJsonObject().toString());
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private void recordSuccess() {
        if (authSuccessCounter != null) {
            authSuccessCounter.increment();
        }
    }

    private void recordFailure(String reason) {
        if (authFailureCounter != null) {
            authFailureCounter.increment();
        }
        LOG.debugf("Authentication failed: %s", reason);
    }

    /**
     * Result of token validation.
     */
    public record ValidationResult(
            boolean valid,
            String subject,
            Set<String> roles,
            JsonObject claims,
            String errorMessage
    ) {
        public static ValidationResult success(String subject, Set<String> roles, JsonObject claims) {
            return new ValidationResult(true, subject, roles, claims, null);
        }

        public static ValidationResult failure(String errorMessage) {
            return new ValidationResult(false, null, Set.of(), null, errorMessage);
        }
    }
}
