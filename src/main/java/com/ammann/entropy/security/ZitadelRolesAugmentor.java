package com.ammann.entropy.security;

import io.quarkus.oidc.TokenIntrospection;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Security Identity Augmentor for ZITADEL role extraction.
 *
 * <p>ZITADEL stores roles in a custom claim format:
 * <pre>
 * "urn:zitadel:iam:org:project:roles": {
 *   "ADMIN_ROLE": { "orgId": "123" },
 *   "USER_ROLE": { "orgId": "123" }
 * }
 * </pre>
 *
 * <p>This augmentor extracts role names from the object keys and adds them to
 * the SecurityIdentity, making them available for @RolesAllowed checks.
 *
 * <p>Supports both:
 * <ul>
 *   <li>JWT tokens (via JsonWebToken claim extraction)</li>
 *   <li>Opaque tokens (via TokenIntrospection attributes)</li>
 * </ul>
 */
@ApplicationScoped
public class ZitadelRolesAugmentor implements SecurityIdentityAugmentor
{

    private static final Logger LOG = Logger.getLogger(ZitadelRolesAugmentor.class);

    /**
     * ZITADEL-specific claim path for project roles (generic format).
     * Must match: quarkus.oidc.roles.role-claim-path
     */
    private static final String ZITADEL_ROLES_CLAIM = "urn:zitadel:iam:org:project:roles";

    /**
     * ZITADEL-specific claim prefix for project-specific roles (with project ID).
     * Format: "urn:zitadel:iam:org:project:{projectId}:roles"
     */
    private static final String ZITADEL_PROJECT_ROLES_PREFIX = "urn:zitadel:iam:org:project:";

    /**
     * Standard OAuth2 scope claim (fallback for simple role strings).
     */
    private static final String SCOPE_CLAIM = "scope";

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity,
                                         AuthenticationRequestContext context)
    {
        return Uni.createFrom().item(augmentBlocking(identity));
    }

    /**
     * Blocking augmentation logic - extracts roles from JWT or introspection response.
     */
    private SecurityIdentity augmentBlocking(SecurityIdentity identity)
    {
        Set<String> extractedRoles = new HashSet<>();

        LOG.debugf("Augmenting security identity for principal: %s, existing roles: %s, attributes: %s",
                identity.getPrincipal().getName(),
                identity.getRoles(),
                identity.getAttributes().keySet());

        // Try JWT token first (for Frontend PKCE or service-to-service JWTs)
        if (identity.getPrincipal() instanceof JsonWebToken jwt) {
            extractedRoles.addAll(extractRolesFromJwt(jwt));
        }

        // Try TokenIntrospection (for opaque tokens from ZITADEL service accounts)
        Object introspectionObj = identity.getAttribute("introspection");

        if (introspectionObj instanceof TokenIntrospection introspection) {
            extractedRoles.addAll(extractRolesFromIntrospection(introspection));
        }

        // If no roles extracted, return identity unchanged
        if (extractedRoles.isEmpty()) {
            LOG.warnf("No ZITADEL roles found for principal: %s (existing roles: %s)",
                    identity.getPrincipal().getName(), identity.getRoles());
            return identity;
        }

        // Build new SecurityIdentity with extracted roles
        LOG.infof("Extracted %d ZITADEL roles for principal '%s': %s",
                extractedRoles.size(), identity.getPrincipal().getName(), extractedRoles);

        return QuarkusSecurityIdentity.builder(identity)
                .addRoles(extractedRoles)
                .build();
    }

    /**
     * Extracts roles from JWT token claims.
     *
     * @param jwt The JSON Web Token
     * @return Set of role names
     */
    private Set<String> extractRolesFromJwt(JsonWebToken jwt)
    {
        Set<String> roles = new HashSet<>();

        try {
            // ZITADEL project roles claim (primary source)
            Object rolesClaim = jwt.getClaim(ZITADEL_ROLES_CLAIM);
            if (rolesClaim instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rolesMap = (Map<String, Object>) rolesClaim;
                roles.addAll(rolesMap.keySet());
                LOG.debugf("Extracted %d roles from JWT claim '%s': %s",
                        roles.size(), ZITADEL_ROLES_CLAIM, roles);
                return roles;
            }

            // Fallback: standard 'groups' claim (if ZITADEL format not found)
            Set<String> groups = jwt.getGroups();
            if (groups != null && !groups.isEmpty()) {
                roles.addAll(groups);
                LOG.debugf("Extracted %d roles from JWT 'groups' claim: %s", roles.size(), roles);
                return roles;
            }

            // Fallback: scope claim (space-separated roles)
            String scope = jwt.getClaim(SCOPE_CLAIM);
            if (scope != null && !scope.isBlank()) {
                for (String s : scope.split("\\s+")) {
                    if (!s.isBlank()) {
                        roles.add(s);
                    }
                }
                LOG.debugf("Extracted %d roles from JWT 'scope' claim: %s", roles.size(), roles);
            }

        } catch (Exception e) {
            LOG.warnf(e, "Failed to extract roles from JWT: %s", e.getMessage());
        }

        return roles;
    }

    /**
     * Extracts roles from token introspection response (opaque tokens).
     *
     * @param introspection The introspection response
     * @return Set of role names
     */
    private Set<String> extractRolesFromIntrospection(TokenIntrospection introspection)
    {
        Set<String> roles = new HashSet<>();

        try {
            LOG.debugf("Introspection claims available: %s", introspection.getJsonObject().keySet());

            // Try generic ZITADEL project roles claim first
            Object rolesClaim = introspection.getJsonObject()
                    .get(ZITADEL_ROLES_CLAIM);

            LOG.debugf("Generic roles claim (%s): %s (type: %s)",
                    ZITADEL_ROLES_CLAIM,
                    rolesClaim,
                    rolesClaim != null ? rolesClaim.getClass().getName() : "null");

            this.addRoles(roles, rolesClaim, ZITADEL_ROLES_CLAIM);

            // Try project-specific roles claims (with project ID in path)
            for (String key : introspection.getJsonObject().keySet()) {
                LOG.debugf("Checking key: %s", key);
                if (!key.startsWith(ZITADEL_PROJECT_ROLES_PREFIX) && key.endsWith(":roles")) {
                    continue;
                }

                Object projectRolesClaim = introspection.getJsonObject().get(key);
                LOG.debugf("Project-specific roles claim (%s): %s (type: %s)",
                        key,
                        projectRolesClaim,
                        projectRolesClaim != null ? projectRolesClaim.getClass().getName() : "null");

                this.addRoles(roles, projectRolesClaim, key);
            }

            // Fallback: scope claim (space-separated roles)
            String scope = introspection.getString(SCOPE_CLAIM);
            if (scope != null && !scope.isBlank()) {
                for (String s : scope.split("\\s+")) {
                    if (!s.isBlank()) {
                        roles.add(s);
                    }
                }
                LOG.debugf("Extracted %d roles from introspection 'scope': %s", roles.size(), roles);
            }

        } catch (Exception e) {
            LOG.errorf(e, "Failed to extract roles from introspection: %s", e.getMessage());
        }

        return roles;
    }


    private void addRoles(Set<String> roles, Object rolesClaim, String key){
        if (!(rolesClaim instanceof Map<?, ?> rawMap)) {
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> rolesMap = (Map<String, Object>) rawMap;

        roles.addAll(rolesMap.keySet());
        LOG.infof("Extracted %d roles from introspection claim '%s': %s",
                roles.size(), key, roles);
    }
}