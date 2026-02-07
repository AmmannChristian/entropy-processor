/* (C)2026 */
package com.ammann.entropy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.oidc.OidcProviderClient;
import io.quarkus.oidc.TokenIntrospection;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.inject.Instance;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

class JwtValidationServiceTest {
    @Test
    void returnsSuccessWhenSecurityDisabled() {
        JwtValidationService service = baseService(mock(Instance.class), mock(Instance.class));
        service.securityEnabled = false;

        var result = service.validateToken("anything");

        assertThat(result.valid()).isTrue();
        assertThat(result.subject()).isNull();
        assertThat(result.roles()).isEmpty();
    }

    @Test
    void rejectsMissingToken() {
        JwtValidationService service = baseService(mock(Instance.class), mock(Instance.class));

        var result = service.validateToken(" ");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorMessage()).contains("Missing token");
    }

    @Test
    void rejectsOpaqueTokenWhenIntrospectionDisabled() {
        JwtValidationService service = baseService(mock(Instance.class), mock(Instance.class));

        var result = service.validateToken("opaque-token");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorMessage()).contains("Opaque token introspection disabled");
    }

    @Test
    void validatesJwtWithSignatureAndGroups() throws Exception {
        JWTParser parser = mock(JWTParser.class);
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://auth.example.com/");
        when(jwt.getAudience()).thenReturn(Set.of("aud"));
        when(jwt.getSubject()).thenReturn("user123");
        when(jwt.getGroups()).thenReturn(Set.of("admin"));
        when(parser.parse("a.b.c")).thenReturn(jwt);

        Instance<JWTParser> jwtParserInstance = mock(Instance.class);
        when(jwtParserInstance.isUnsatisfied()).thenReturn(false);
        when(jwtParserInstance.get()).thenReturn(parser);

        JwtValidationService service = baseService(jwtParserInstance, mock(Instance.class));
        service.expectedAudience = Optional.of("aud");

        var result = service.validateToken("a.b.c");

        assertThat(result.valid()).isTrue();
        assertThat(result.subject()).isEqualTo("user123");
        assertThat(result.roles()).contains("admin");
    }

    @Test
    void rejectsInvalidIssuer() throws Exception {
        JWTParser parser = mock(JWTParser.class);
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://evil.example.com/");
        when(jwt.getAudience()).thenReturn(Set.of("aud"));
        when(jwt.getGroups()).thenReturn(Set.of());
        when(parser.parse(anyString())).thenReturn(jwt);

        Instance<JWTParser> jwtParserInstance = mock(Instance.class);
        when(jwtParserInstance.isUnsatisfied()).thenReturn(false);
        when(jwtParserInstance.get()).thenReturn(parser);

        JwtValidationService service = baseService(jwtParserInstance, mock(Instance.class));
        service.expectedAudience = Optional.of("aud");

        var result = service.validateToken("a.b.c");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorMessage()).contains("Invalid issuer");
    }

    @Test
    void rejectsInvalidAudience() throws Exception {
        JWTParser parser = mock(JWTParser.class);
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://auth.example.com/");
        when(jwt.getAudience()).thenReturn(Set.of("other"));
        when(jwt.getGroups()).thenReturn(Set.of());
        when(parser.parse(anyString())).thenReturn(jwt);

        Instance<JWTParser> jwtParserInstance = mock(Instance.class);
        when(jwtParserInstance.isUnsatisfied()).thenReturn(false);
        when(jwtParserInstance.get()).thenReturn(parser);

        JwtValidationService service = baseService(jwtParserInstance, mock(Instance.class));
        service.expectedAudience = Optional.of("aud");

        var result = service.validateToken("a.b.c");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorMessage()).contains("Invalid audience");
    }

    @Test
    void rejectsOnParseException() throws Exception {
        JWTParser parser = mock(JWTParser.class);
        when(parser.parse(anyString())).thenThrow(new ParseException("bad"));

        Instance<JWTParser> jwtParserInstance = mock(Instance.class);
        when(jwtParserInstance.isUnsatisfied()).thenReturn(false);
        when(jwtParserInstance.get()).thenReturn(parser);

        JwtValidationService service = baseService(jwtParserInstance, mock(Instance.class));

        var result = service.validateToken("a.b.c");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorMessage()).contains("signature validation failed");
    }

    @Test
    void rejectsWhenParserUnavailableAndNoIntrospection() {
        Instance<JWTParser> jwtParserInstance = mock(Instance.class);
        when(jwtParserInstance.isUnsatisfied()).thenReturn(true);

        JwtValidationService service = baseService(jwtParserInstance, mock(Instance.class));

        var result = service.validateToken("a.b.c");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorMessage()).contains("signature validation not available");
    }

    @Test
    void introspectsOpaqueTokenWhenEnabled() {
        TokenIntrospection introspection = mock(TokenIntrospection.class);
        when(introspection.isActive()).thenReturn(true);
        when(introspection.getSubject()).thenReturn("subject-1");
        JsonObject json = Json.createObjectBuilder().add("scope", "read write").build();
        when(introspection.getJsonObject()).thenReturn(json);
        when(introspection.getString("scope")).thenReturn("read write");

        OidcProviderClient oidcClient = mock(OidcProviderClient.class);
        when(oidcClient.introspectAccessToken("opaque"))
                .thenReturn(Uni.createFrom().item(introspection));

        Instance<OidcProviderClient> oidcInstance = mock(Instance.class);
        when(oidcInstance.isUnsatisfied()).thenReturn(false);
        when(oidcInstance.get()).thenReturn(oidcClient);

        JwtValidationService service = baseService(mock(Instance.class), oidcInstance);
        service.allowOpaqueTokenIntrospection = true;

        var result = service.validateToken("opaque");

        assertThat(result.valid()).isTrue();
        assertThat(result.subject()).isEqualTo("subject-1");
        assertThat(result.roles()).contains("read", "write");
    }

    @Test
    void validateTokenAsyncRecoversOnIntrospectionError() {
        OidcProviderClient oidcClient = mock(OidcProviderClient.class);
        when(oidcClient.introspectAccessToken("opaque"))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("boom")));

        Instance<OidcProviderClient> oidcInstance = mock(Instance.class);
        when(oidcInstance.isUnsatisfied()).thenReturn(false);
        when(oidcInstance.get()).thenReturn(oidcClient);

        JwtValidationService service = baseService(mock(Instance.class), oidcInstance);
        service.allowOpaqueTokenIntrospection = true;

        var result = service.validateTokenAsync("opaque").await().indefinitely();

        assertThat(result.valid()).isFalse();
        assertThat(result.errorMessage()).contains("Token introspection failed");
    }

    @Test
    void validateTokenAsyncRejectsMissingToken() {
        JwtValidationService service = baseService(mock(Instance.class), mock(Instance.class));

        var result = service.validateTokenAsync(" ").await().indefinitely();

        assertThat(result.valid()).isFalse();
        assertThat(result.errorMessage()).contains("Missing token");
    }

    @Test
    void validateTokenAsyncRejectsWhenParserUnavailableAndNoIntrospection() {
        Instance<JWTParser> jwtParserInstance = mock(Instance.class);
        when(jwtParserInstance.isUnsatisfied()).thenReturn(true);

        JwtValidationService service = baseService(jwtParserInstance, mock(Instance.class));

        var result = service.validateTokenAsync("a.b.c").await().indefinitely();

        assertThat(result.valid()).isFalse();
        assertThat(result.errorMessage()).contains("signature validation not available");
    }

    @Test
    void validateTokenAsyncUsesSignatureValidation() throws Exception {
        JWTParser parser = mock(JWTParser.class);
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://auth.example.com/");
        when(jwt.getAudience()).thenReturn(Set.of("aud"));
        when(jwt.getSubject()).thenReturn("user123");
        when(jwt.getGroups()).thenReturn(Set.of("admin"));
        when(parser.parse("a.b.c")).thenReturn(jwt);

        Instance<JWTParser> jwtParserInstance = mock(Instance.class);
        when(jwtParserInstance.isUnsatisfied()).thenReturn(false);
        when(jwtParserInstance.get()).thenReturn(parser);

        JwtValidationService service = baseService(jwtParserInstance, mock(Instance.class));

        var result = service.validateTokenAsync("a.b.c").await().indefinitely();

        assertThat(result.valid()).isTrue();
        assertThat(result.roles()).contains("admin");
    }

    @Test
    void rejectsOpaqueTokenWhenIntrospectionClientUnavailable() {
        Instance<OidcProviderClient> oidcInstance = mock(Instance.class);
        when(oidcInstance.isUnsatisfied()).thenReturn(true);

        JwtValidationService service = baseService(mock(Instance.class), oidcInstance);
        service.allowOpaqueTokenIntrospection = true;

        var result = service.validateToken("opaque");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorMessage()).contains("Token introspection not available");
    }

    @Test
    void extractsRolesFromIntrospectionZitadelClaim() {
        TokenIntrospection introspection = mock(TokenIntrospection.class);
        when(introspection.isActive()).thenReturn(true);
        when(introspection.getSubject()).thenReturn("subject-3");

        JsonObject json =
                jsonObjectProxy(
                        Map.of(
                                "urn:zitadel:iam:org:project:roles",
                                Map.of("admin", true, "writer", true)),
                        Set.of());
        when(introspection.getJsonObject()).thenReturn(json);

        OidcProviderClient oidcClient = mock(OidcProviderClient.class);
        when(oidcClient.introspectAccessToken("opaque"))
                .thenReturn(Uni.createFrom().item(introspection));

        Instance<OidcProviderClient> oidcInstance = mock(Instance.class);
        when(oidcInstance.isUnsatisfied()).thenReturn(false);
        when(oidcInstance.get()).thenReturn(oidcClient);

        JwtValidationService service = baseService(mock(Instance.class), oidcInstance);
        service.allowOpaqueTokenIntrospection = true;

        var result = service.validateToken("opaque");

        assertThat(result.valid()).isTrue();
        assertThat(result.roles()).contains("admin", "writer");
    }

    @Test
    void extractsRolesFromIntrospectionProjectSpecificClaim() {
        TokenIntrospection introspection = mock(TokenIntrospection.class);
        when(introspection.isActive()).thenReturn(true);
        when(introspection.getSubject()).thenReturn("subject-4");

        String claim = "urn:zitadel:iam:org:project:abc:roles";
        JsonObject json =
                jsonObjectProxy(
                        Map.of(claim, Map.of("reader", true)),
                        Set.of(claim));
        when(introspection.getJsonObject()).thenReturn(json);

        OidcProviderClient oidcClient = mock(OidcProviderClient.class);
        when(oidcClient.introspectAccessToken("opaque"))
                .thenReturn(Uni.createFrom().item(introspection));

        Instance<OidcProviderClient> oidcInstance = mock(Instance.class);
        when(oidcInstance.isUnsatisfied()).thenReturn(false);
        when(oidcInstance.get()).thenReturn(oidcClient);

        JwtValidationService service = baseService(mock(Instance.class), oidcInstance);
        service.allowOpaqueTokenIntrospection = true;

        var result = service.validateToken("opaque");

        assertThat(result.valid()).isTrue();
        assertThat(result.roles()).contains("reader");
    }

    @Test
    void acceptsAnyIssuerWhenIssuerUrlBlank() throws Exception {
        JWTParser parser = mock(JWTParser.class);
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://any-issuer/");
        when(jwt.getAudience()).thenReturn(Set.of());
        when(jwt.getGroups()).thenReturn(Set.of("role-1"));
        when(parser.parse("a.b.c")).thenReturn(jwt);

        Instance<JWTParser> jwtParserInstance = mock(Instance.class);
        when(jwtParserInstance.isUnsatisfied()).thenReturn(false);
        when(jwtParserInstance.get()).thenReturn(parser);

        JwtValidationService service = baseService(jwtParserInstance, mock(Instance.class));
        service.issuerUrl = "";
        service.expectedAudience = Optional.empty();

        var result = service.validateToken("a.b.c");

        assertThat(result.valid()).isTrue();
        assertThat(result.roles()).contains("role-1");
    }

    @Test
    void rejectsNullIssuer() throws Exception {
        JWTParser parser = mock(JWTParser.class);
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn(null);
        when(jwt.getAudience()).thenReturn(Set.of("aud"));
        when(jwt.getGroups()).thenReturn(Set.of());
        when(parser.parse("a.b.c")).thenReturn(jwt);

        Instance<JWTParser> jwtParserInstance = mock(Instance.class);
        when(jwtParserInstance.isUnsatisfied()).thenReturn(false);
        when(jwtParserInstance.get()).thenReturn(parser);

        JwtValidationService service = baseService(jwtParserInstance, mock(Instance.class));

        var result = service.validateToken("a.b.c");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorMessage()).contains("Invalid issuer");
    }

    @Test
    void extractsRolesFromZitadelClaim() throws Exception {
        JWTParser parser = mock(JWTParser.class);
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://auth.example.com/");
        when(jwt.getAudience()).thenReturn(Set.of());
        when(jwt.getClaim("urn:zitadel:iam:org:project:roles")).thenReturn(Map.of("admin", true));
        when(parser.parse("a.b.c")).thenReturn(jwt);

        Instance<JWTParser> jwtParserInstance = mock(Instance.class);
        when(jwtParserInstance.isUnsatisfied()).thenReturn(false);
        when(jwtParserInstance.get()).thenReturn(parser);

        JwtValidationService service = baseService(jwtParserInstance, mock(Instance.class));
        service.expectedAudience = Optional.empty();

        var result = service.validateToken("a.b.c");

        assertThat(result.valid()).isTrue();
        assertThat(result.roles()).contains("admin");
    }

    @Test
    void extractsRolesFromProjectSpecificClaim() throws Exception {
        JWTParser parser = mock(JWTParser.class);
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://auth.example.com/");
        when(jwt.getAudience()).thenReturn(Set.of());
        when(jwt.getClaim("urn:zitadel:iam:org:project:roles")).thenReturn(null);
        when(jwt.getClaimNames()).thenReturn(Set.of("urn:zitadel:iam:org:project:abc:roles"));
        when(jwt.getClaim("urn:zitadel:iam:org:project:abc:roles"))
                .thenReturn(Map.of("writer", true));
        when(parser.parse("a.b.c")).thenReturn(jwt);

        Instance<JWTParser> jwtParserInstance = mock(Instance.class);
        when(jwtParserInstance.isUnsatisfied()).thenReturn(false);
        when(jwtParserInstance.get()).thenReturn(parser);

        JwtValidationService service = baseService(jwtParserInstance, mock(Instance.class));
        service.expectedAudience = Optional.empty();

        var result = service.validateToken("a.b.c");

        assertThat(result.valid()).isTrue();
        assertThat(result.roles()).contains("writer");
    }

    @Test
    void extractsRolesFromStandardRolesClaim() throws Exception {
        JWTParser parser = mock(JWTParser.class);
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://auth.example.com/");
        when(jwt.getAudience()).thenReturn(Set.of());
        when(jwt.getGroups()).thenReturn(Set.of());
        when(jwt.getClaim("roles")).thenReturn(List.of("reader", "writer"));
        when(parser.parse("a.b.c")).thenReturn(jwt);

        Instance<JWTParser> jwtParserInstance = mock(Instance.class);
        when(jwtParserInstance.isUnsatisfied()).thenReturn(false);
        when(jwtParserInstance.get()).thenReturn(parser);

        JwtValidationService service = baseService(jwtParserInstance, mock(Instance.class));
        service.expectedAudience = Optional.empty();

        var result = service.validateToken("a.b.c");

        assertThat(result.valid()).isTrue();
        assertThat(result.roles()).contains("reader", "writer");
    }

    @Test
    void validateTokenAsyncUsesIntrospectionWhenAllowed() {
        TokenIntrospection introspection = mock(TokenIntrospection.class);
        when(introspection.isActive()).thenReturn(true);
        when(introspection.getSubject()).thenReturn("subject-2");
        when(introspection.getJsonObject()).thenReturn(Json.createObjectBuilder().build());

        OidcProviderClient oidcClient = mock(OidcProviderClient.class);
        when(oidcClient.introspectAccessToken("opaque"))
                .thenReturn(Uni.createFrom().item(introspection));

        Instance<OidcProviderClient> oidcInstance = mock(Instance.class);
        when(oidcInstance.isUnsatisfied()).thenReturn(false);
        when(oidcInstance.get()).thenReturn(oidcClient);

        Instance<JWTParser> jwtParserInstance = mock(Instance.class);
        when(jwtParserInstance.isUnsatisfied()).thenReturn(true);

        JwtValidationService service = baseService(jwtParserInstance, oidcInstance);
        service.allowJwtIntrospection = true;
        service.allowOpaqueTokenIntrospection = true;

        var result = service.validateTokenAsync("opaque").await().indefinitely();

        assertThat(result.valid()).isTrue();
        assertThat(result.subject()).isEqualTo("subject-2");
    }

    @Test
    void validateWithIntrospectionRejectsInactiveToken() {
        TokenIntrospection introspection = mock(TokenIntrospection.class);
        when(introspection.isActive()).thenReturn(false);

        OidcProviderClient oidcClient = mock(OidcProviderClient.class);
        when(oidcClient.introspectAccessToken("opaque"))
                .thenReturn(Uni.createFrom().item(introspection));

        Instance<OidcProviderClient> oidcInstance = mock(Instance.class);
        when(oidcInstance.isUnsatisfied()).thenReturn(false);
        when(oidcInstance.get()).thenReturn(oidcClient);

        JwtValidationService service = baseService(mock(Instance.class), oidcInstance);
        service.allowOpaqueTokenIntrospection = true;

        var result = service.validateToken("opaque");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorMessage()).contains("not active");
    }

    @Test
    void validateWithIntrospectionRejectsEmptyResponse() {
        OidcProviderClient oidcClient = mock(OidcProviderClient.class);
        when(oidcClient.introspectAccessToken("opaque"))
                .thenReturn(Uni.createFrom().item((TokenIntrospection) null));

        Instance<OidcProviderClient> oidcInstance = mock(Instance.class);
        when(oidcInstance.isUnsatisfied()).thenReturn(false);
        when(oidcInstance.get()).thenReturn(oidcClient);

        JwtValidationService service = baseService(mock(Instance.class), oidcInstance);
        service.allowOpaqueTokenIntrospection = true;

        var result = service.validateToken("opaque");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorMessage()).contains("empty response");
    }

    private static JwtValidationService baseService(
            Instance<JWTParser> jwtParserInstance, Instance<OidcProviderClient> oidcInstance) {
        JwtValidationService service = new JwtValidationService();
        service.meterRegistry = new SimpleMeterRegistry();
        service.securityEnabled = true;
        service.jwtParserInstance = jwtParserInstance;
        service.oidcProviderClientInstance = oidcInstance;
        service.issuerUrl = "https://auth.example.com/";
        service.expectedAudience = Optional.of("aud");
        service.allowOpaqueTokenIntrospection = false;
        service.allowJwtIntrospection = false;
        service.init();
        return service;
    }

    private static JsonObject jsonObjectProxy(Map<String, Object> values, Set<String> keys) {
        return (JsonObject)
                Proxy.newProxyInstance(
                        JsonObject.class.getClassLoader(),
                        new Class<?>[] {JsonObject.class},
                        (proxy, method, args) -> {
                            return switch (method.getName()) {
                                case "get" -> values.get(args[0]);
                                case "keySet" -> keys;
                                case "toString" -> "{}";
                                default -> null;
                            };
                        });
    }
}
