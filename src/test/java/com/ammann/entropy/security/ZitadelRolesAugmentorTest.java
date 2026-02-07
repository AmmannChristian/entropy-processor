/* (C)2026 */
package com.ammann.entropy.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.quarkus.oidc.TokenIntrospection;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import java.security.Principal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ZitadelRolesAugmentor")
class ZitadelRolesAugmentorTest {

    ZitadelRolesAugmentor augmentor;
    AuthenticationRequestContext context;
    Logger logger;

    @BeforeEach
    void setUp() throws Exception {
        augmentor = new ZitadelRolesAugmentor();
        context = mock(AuthenticationRequestContext.class);
        logger = mock(Logger.class);
    }

    @Nested
    @DisplayName("JWT Token Processing")
    class JwtTokenProcessing {

        @Test
        @DisplayName("should extract roles from ZITADEL project roles claim")
        void shouldExtractRolesFromZitadelClaim() {
            JsonWebToken jwt = mock(JsonWebToken.class);
            when(jwt.getName()).thenReturn("test-user");
            when(jwt.getClaimNames()).thenReturn(Set.of("urn:zitadel:iam:org:project:roles"));

            Map<String, Object> rolesMap = new HashMap<>();
            rolesMap.put("ADMIN_ROLE", Map.of("orgId", "123"));
            rolesMap.put("USER_ROLE", Map.of("orgId", "123"));

            when(jwt.getClaim("urn:zitadel:iam:org:project:roles")).thenReturn(rolesMap);

            SecurityIdentity identity = createIdentityWithJwt(jwt);

            Uni<SecurityIdentity> result = augmentor.augment(identity, context);
            SecurityIdentity augmented = result.await().indefinitely();

            assertThat(augmented.getRoles()).contains("ADMIN_ROLE", "USER_ROLE");
        }

        @Test
        @DisplayName("should fallback to groups claim when ZITADEL claim not found")
        void shouldFallbackToGroupsClaim() {
            JsonWebToken jwt = mock(JsonWebToken.class);
            when(jwt.getName()).thenReturn("test-user");
            when(jwt.getClaimNames()).thenReturn(Collections.emptySet());
            when(jwt.getClaim("urn:zitadel:iam:org:project:roles")).thenReturn(null);
            when(jwt.getGroups()).thenReturn(Set.of("group-admin", "group-user"));

            SecurityIdentity identity = createIdentityWithJwt(jwt);

            Uni<SecurityIdentity> result = augmentor.augment(identity, context);
            SecurityIdentity augmented = result.await().indefinitely();

            assertThat(augmented.getRoles()).contains("group-admin", "group-user");
        }

        @Test
        @DisplayName("should fallback to scope claim when no other claims found")
        void shouldFallbackToScopeClaim() {
            JsonWebToken jwt = mock(JsonWebToken.class);
            when(jwt.getName()).thenReturn("test-user");
            when(jwt.getClaimNames()).thenReturn(Collections.emptySet());
            when(jwt.getClaim("urn:zitadel:iam:org:project:roles")).thenReturn(null);
            when(jwt.getGroups()).thenReturn(Collections.emptySet());
            when(jwt.getClaim("scope")).thenReturn("read write admin");

            SecurityIdentity identity = createIdentityWithJwt(jwt);

            Uni<SecurityIdentity> result = augmentor.augment(identity, context);
            SecurityIdentity augmented = result.await().indefinitely();

            assertThat(augmented.getRoles()).contains("read", "write", "admin");
        }

        @Test
        @DisplayName("should handle exception during role extraction")
        void shouldHandleExceptionDuringRoleExtraction() {
            JsonWebToken jwt = mock(JsonWebToken.class);
            when(jwt.getName()).thenReturn("test-user");
            when(jwt.getClaim("urn:zitadel:iam:org:project:roles"))
                    .thenThrow(new RuntimeException("Token parsing error"));

            SecurityIdentity identity = createIdentityWithJwt(jwt);

            Uni<SecurityIdentity> result = augmentor.augment(identity, context);
            SecurityIdentity augmented = result.await().indefinitely();

            // Should return original identity when extraction fails
            assertThat(augmented).isSameAs(identity);
        }

        @Test
        @DisplayName("should skip blank scope values")
        void shouldSkipBlankScopeValues() {
            JsonWebToken jwt = mock(JsonWebToken.class);
            when(jwt.getName()).thenReturn("test-user");
            when(jwt.getClaimNames()).thenReturn(Collections.emptySet());
            when(jwt.getClaim("urn:zitadel:iam:org:project:roles")).thenReturn(null);
            when(jwt.getGroups()).thenReturn(Collections.emptySet());
            when(jwt.getClaim("scope")).thenReturn("read   write  ");

            SecurityIdentity identity = createIdentityWithJwt(jwt);

            Uni<SecurityIdentity> result = augmentor.augment(identity, context);
            SecurityIdentity augmented = result.await().indefinitely();

            assertThat(augmented.getRoles()).containsExactlyInAnyOrder("read", "write");
        }
    }

    @Nested
    @DisplayName("Token Introspection Processing")
    class TokenIntrospectionProcessing {

        @Test
        @DisplayName("should extract roles from introspection response")
        void shouldExtractRolesFromIntrospection() {
            SecurityIdentity identity =
                    createIdentityWithIntrospection(Map.of("ADMIN_ROLE", Map.of("orgId", "123")));

            Uni<SecurityIdentity> result = augmentor.augment(identity, context);
            SecurityIdentity augmented = result.await().indefinitely();

            assertThat(augmented.getRoles()).contains("ADMIN_ROLE");
        }

        @Test
        @DisplayName("should extract roles from project-specific introspection claim")
        void shouldExtractRolesFromProjectSpecificIntrospectionClaim() {
            JsonObject rolesObj =
                    Json.createObjectBuilder()
                            .add("SUPER_ADMIN", Json.createObjectBuilder().add("orgId", "456"))
                            .build();
            JsonObject jsonObject =
                    Json.createObjectBuilder()
                            .add("urn:zitadel:iam:org:project:99999:roles", rolesObj)
                            .build();

            TokenIntrospection introspection = mock(TokenIntrospection.class);
            when(introspection.getJsonObject()).thenReturn(jsonObject);

            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn("service-account");

            SecurityIdentity identity = mock(SecurityIdentity.class);
            when(identity.getPrincipal()).thenReturn(principal);
            when(identity.getRoles()).thenReturn(Collections.emptySet());
            when(identity.getAttributes()).thenReturn(Map.of("introspection", introspection));
            when(identity.getAttribute("introspection")).thenReturn(introspection);

            Uni<SecurityIdentity> result = augmentor.augment(identity, context);
            SecurityIdentity augmented = result.await().indefinitely();

            assertThat(augmented.getRoles()).contains("SUPER_ADMIN");
        }

        @Test
        @DisplayName("should fallback to scope in introspection")
        void shouldFallbackToScopeInIntrospection() {
            JsonObject jsonObject = Json.createObjectBuilder().build();

            TokenIntrospection introspection = mock(TokenIntrospection.class);
            when(introspection.getJsonObject()).thenReturn(jsonObject);
            when(introspection.getString("scope")).thenReturn("read write");

            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn("service-account");

            SecurityIdentity identity = mock(SecurityIdentity.class);
            when(identity.getPrincipal()).thenReturn(principal);
            when(identity.getRoles()).thenReturn(Collections.emptySet());
            when(identity.getAttributes()).thenReturn(Map.of("introspection", introspection));
            when(identity.getAttribute("introspection")).thenReturn(introspection);

            Uni<SecurityIdentity> result = augmentor.augment(identity, context);
            SecurityIdentity augmented = result.await().indefinitely();

            assertThat(augmented.getRoles()).contains("read", "write");
        }

        @Test
        @DisplayName("should handle exception during introspection processing")
        void shouldHandleExceptionDuringIntrospectionProcessing() {
            TokenIntrospection introspection = mock(TokenIntrospection.class);
            when(introspection.getJsonObject()).thenThrow(new RuntimeException("Parse error"));

            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn("service-account");

            SecurityIdentity identity = mock(SecurityIdentity.class);
            when(identity.getPrincipal()).thenReturn(principal);
            when(identity.getRoles()).thenReturn(Collections.emptySet());
            when(identity.getAttributes()).thenReturn(Map.of("introspection", introspection));
            when(identity.getAttribute("introspection")).thenReturn(introspection);

            Uni<SecurityIdentity> result = augmentor.augment(identity, context);
            SecurityIdentity augmented = result.await().indefinitely();

            assertThat(augmented).isNotNull();
        }
    }

    @Nested
    @DisplayName("No Roles Found")
    class NoRolesFound {

        @Test
        @DisplayName("should return unchanged identity when no roles found")
        void shouldReturnUnchangedIdentityWhenNoRolesFound() {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn("anonymous");

            SecurityIdentity identity = mock(SecurityIdentity.class);
            when(identity.getPrincipal()).thenReturn(principal);
            when(identity.getRoles()).thenReturn(Collections.emptySet());
            when(identity.getAttributes()).thenReturn(Collections.emptyMap());
            when(identity.getAttribute("introspection")).thenReturn(null);

            Uni<SecurityIdentity> result = augmentor.augment(identity, context);
            SecurityIdentity augmented = result.await().indefinitely();

            // Should return original identity
            assertThat(augmented).isSameAs(identity);
        }

        @Test
        @DisplayName("should warn when no roles found for principal")
        void shouldWarnWhenNoRolesFoundForPrincipal() {
            JsonWebToken jwt = mock(JsonWebToken.class);
            when(jwt.getName()).thenReturn("test-user");
            when(jwt.getClaimNames()).thenReturn(Collections.emptySet());
            when(jwt.getClaim("urn:zitadel:iam:org:project:roles")).thenReturn(null);
            when(jwt.getGroups()).thenReturn(null);
            when(jwt.getClaim("scope")).thenReturn(null);

            SecurityIdentity identity = createIdentityWithJwt(jwt);

            Uni<SecurityIdentity> result = augmentor.augment(identity, context);
            SecurityIdentity augmented = result.await().indefinitely();

            // Should return original identity
            assertThat(augmented).isSameAs(identity);
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("should handle empty scope string")
        void shouldHandleEmptyScopeString() {
            JsonWebToken jwt = mock(JsonWebToken.class);
            when(jwt.getName()).thenReturn("test-user");
            when(jwt.getClaimNames()).thenReturn(Collections.emptySet());
            when(jwt.getClaim("urn:zitadel:iam:org:project:roles")).thenReturn(null);
            when(jwt.getGroups()).thenReturn(Collections.emptySet());
            when(jwt.getClaim("scope")).thenReturn("");

            SecurityIdentity identity = createIdentityWithJwt(jwt);

            Uni<SecurityIdentity> result = augmentor.augment(identity, context);
            SecurityIdentity augmented = result.await().indefinitely();

            // Should return original identity
            assertThat(augmented).isSameAs(identity);
        }

        @Test
        @DisplayName("should handle blank scope string")
        void shouldHandleBlankScopeString() {
            JsonWebToken jwt = mock(JsonWebToken.class);
            when(jwt.getName()).thenReturn("test-user");
            when(jwt.getClaimNames()).thenReturn(Collections.emptySet());
            when(jwt.getClaim("urn:zitadel:iam:org:project:roles")).thenReturn(null);
            when(jwt.getGroups()).thenReturn(Collections.emptySet());
            when(jwt.getClaim("scope")).thenReturn("   ");

            SecurityIdentity identity = createIdentityWithJwt(jwt);

            Uni<SecurityIdentity> result = augmentor.augment(identity, context);
            SecurityIdentity augmented = result.await().indefinitely();

            // Should return original identity
            assertThat(augmented).isSameAs(identity);
        }
    }

    private SecurityIdentity createIdentityWithJwt(JsonWebToken jwt) {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.getPrincipal()).thenReturn(jwt);
        when(identity.getRoles()).thenReturn(Collections.emptySet());
        when(identity.getAttributes()).thenReturn(Collections.emptyMap());
        when(identity.getAttribute("introspection")).thenReturn(null);
        return identity;
    }

    private SecurityIdentity createIdentityWithIntrospection(Map<String, Object> roles) {
        JsonObjectBuilder rolesBuilder = Json.createObjectBuilder();
        for (Map.Entry<String, Object> entry : roles.entrySet()) {
            if (entry.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) entry.getValue();
                JsonObjectBuilder nestedBuilder = Json.createObjectBuilder();
                for (Map.Entry<String, Object> nestedEntry : nestedMap.entrySet()) {
                    nestedBuilder.add(nestedEntry.getKey(), nestedEntry.getValue().toString());
                }
                rolesBuilder.add(entry.getKey(), nestedBuilder);
            }
        }

        JsonObject jsonObject =
                Json.createObjectBuilder()
                        .add("urn:zitadel:iam:org:project:roles", rolesBuilder)
                        .build();

        TokenIntrospection introspection = mock(TokenIntrospection.class);
        when(introspection.getJsonObject()).thenReturn(jsonObject);

        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn("service-account");

        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.getPrincipal()).thenReturn(principal);
        when(identity.getRoles()).thenReturn(Collections.emptySet());
        when(identity.getAttributes()).thenReturn(Map.of("introspection", introspection));
        when(identity.getAttribute("introspection")).thenReturn(introspection);

        return identity;
    }
}
