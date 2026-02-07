package com.ammann.entropy.interceptor;

import com.ammann.entropy.service.JwtValidationService;
import com.ammann.entropy.service.JwtValidationService.ValidationResult;
import io.grpc.*;
import io.quarkus.grpc.GlobalInterceptor;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * gRPC server interceptor for OIDC/JWT authentication.
 * Validates Bearer tokens using the JwtValidationService.
 * Skips authentication for health checks and gRPC reflection service.
 */
@ApplicationScoped
@GlobalInterceptor
public class OidcAuthInterceptor implements ServerInterceptor {

    private static final Logger LOG = Logger.getLogger(OidcAuthInterceptor.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private static final Metadata.Key<String> AUTH_HEADER =
            Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);

    /**
     * Context key for the authenticated subject (user/service ID).
     */
    public static final Context.Key<String> SUBJECT_CTX_KEY = Context.key("auth-subject");

    /**
     * Context key for the authenticated user's roles.
     */
    public static final Context.Key<Set<String>> ROLES_CTX_KEY = Context.key("auth-roles");

    @ConfigProperty(name = "entropy.security.enabled", defaultValue = "true")
    boolean securityEnabled;

    @Inject
    JwtValidationService jwtValidationService;

    @Override
    public <R, T> ServerCall.Listener<R> interceptCall(
            ServerCall<R, T> call,
            Metadata headers,
            ServerCallHandler<R, T> next) {

        String methodName = call.getMethodDescriptor().getFullMethodName();

        // Check if security is globally disabled
        if (!securityEnabled) {
            LOG.debugf("Security disabled, allowing request to: %s", methodName);
            return next.startCall(call, headers);
        }

        // Skip auth for public endpoints
        if (isPublicEndpoint(methodName)) {
            LOG.debugf("Public endpoint, skipping auth: %s", methodName);
            return next.startCall(call, headers);
        }

        // Extract Authorization header
        String authHeader = headers.get(AUTH_HEADER);

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            LOG.warnf("Missing or invalid Authorization header for: %s", methodName);
            return closeWithUnauthenticated(call, "Missing Bearer token");
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        DeferredListener<R> deferred = new DeferredListener<>();
        io.vertx.core.Context vertxContext = Vertx.currentContext();
        Consumer<Runnable> runOnContext = action -> {
            if (vertxContext != null) {
                vertxContext.runOnContext(ignored -> action.run());
            } else {
                action.run();
            }
        };

        jwtValidationService.validateTokenAsync(token)
                .subscribe().with(
                        result -> runOnContext.accept(() -> handleAuthResult(result, methodName, call, headers, next, deferred)),
                        failure -> runOnContext.accept(() -> {
                            LOG.warnf(failure, "Token validation failed for %s: %s", methodName, failure.getMessage());
                            closeWithUnauthenticated(call, "Token validation failed");
                            deferred.fail();
                        })
                );

        return deferred;
    }

    /**
     * Checks if the endpoint is public (no auth required).
     */
    private boolean isPublicEndpoint(String methodName) {
        return methodName.contains("Health") ||
               methodName.contains("grpc.reflection") ||
               methodName.contains("ServerReflection");
    }

    /**
     * Closes the call with UNAUTHENTICATED status.
     */
    private <R, T> ServerCall.Listener<R> closeWithUnauthenticated(
            ServerCall<R, T> call, String description) {
        call.close(Status.UNAUTHENTICATED.withDescription(description), new Metadata());
        return new ServerCall.Listener<>() {};
    }

    private <R, T> void handleAuthResult(ValidationResult result,
                                         String methodName,
                                         ServerCall<R, T> call,
                                         Metadata headers,
                                         ServerCallHandler<R, T> next,
                                         DeferredListener<R> deferred) {
        if (!result.valid()) {
            LOG.warnf("Token validation failed for %s: %s", methodName, result.errorMessage());
            closeWithUnauthenticated(call, result.errorMessage());
            deferred.fail();
            return;
        }

        Context ctx = Context.current()
                .withValue(SUBJECT_CTX_KEY, result.subject())
                .withValue(ROLES_CTX_KEY, result.roles());

        LOG.debugf("Authenticated request to %s from subject: %s", methodName, result.subject());
        deferred.setDelegate(Contexts.interceptCall(ctx, call, headers, next));
    }

    private static final class DeferredListener<R> extends ServerCall.Listener<R> {
        private static final ServerCall.Listener<Object> NOOP = new ServerCall.Listener<>() {};

        private final Queue<Consumer<ServerCall.Listener<R>>> pending = new ConcurrentLinkedQueue<>();
        private volatile ServerCall.Listener<R> delegate;

        void setDelegate(ServerCall.Listener<R> delegate) {
            this.delegate = delegate;
            drain(delegate);
        }

        void fail() {
            @SuppressWarnings("unchecked")
            ServerCall.Listener<R> noop = (ServerCall.Listener<R>) NOOP;
            this.delegate = noop;
            pending.clear();
        }

        private void drain(ServerCall.Listener<R> delegate) {
            Consumer<ServerCall.Listener<R>> action;
            while ((action = pending.poll()) != null) {
                action.accept(delegate);
            }
        }

        private void runOrQueue(Consumer<ServerCall.Listener<R>> action) {
            ServerCall.Listener<R> current = delegate;
            if (current != null) {
                action.accept(current);
                return;
            }
            pending.add(action);
            current = delegate;
            if (current != null) {
                drain(current);
            }
        }

        @Override
        public void onMessage(R message) {
            runOrQueue(listener -> listener.onMessage(message));
        }

        @Override
        public void onHalfClose() {
            runOrQueue(ServerCall.Listener::onHalfClose);
        }

        @Override
        public void onCancel() {
            runOrQueue(ServerCall.Listener::onCancel);
        }

        @Override
        public void onComplete() {
            runOrQueue(ServerCall.Listener::onComplete);
        }

        @Override
        public void onReady() {
            runOrQueue(ServerCall.Listener::onReady);
        }
    }

    /**
     * Utility method to get the current authenticated subject from context.
     */
    public static String getCurrentSubject() {
        return SUBJECT_CTX_KEY.get();
    }

    /**
     * Utility method to get the current authenticated roles from context.
     */
    public static Set<String> getCurrentRoles() {
        Set<String> roles = ROLES_CTX_KEY.get();
        return roles != null ? roles : Set.of();
    }

    /**
     * Utility method to check if current user has a specific role.
     */
    public static boolean hasRole(String role) {
        return getCurrentRoles().contains(role);
    }
}
