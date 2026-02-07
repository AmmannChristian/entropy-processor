/* (C)2026 */
package com.ammann.entropy.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ammann.entropy.service.JwtValidationService;
import com.google.protobuf.Empty;
import io.grpc.*;
import io.grpc.protobuf.ProtoUtils;
import io.smallrye.mutiny.Uni;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OidcAuthInterceptorTest {
    @Test
    void skipsAuthWhenSecurityDisabled() {
        OidcAuthInterceptor interceptor = new OidcAuthInterceptor();
        interceptor.securityEnabled = false;
        interceptor.jwtValidationService = mock(JwtValidationService.class);

        CapturingCall call = capturingCall("service/Method");
        AtomicBoolean started = new AtomicBoolean();

        interceptor.interceptCall(call, new Metadata(), startRecording(started));

        assertThat(started).isTrue();
        assertThat(call.closeStatus).isNull();
    }

    @Test
    void skipsPublicEndpoints() {
        OidcAuthInterceptor interceptor = new OidcAuthInterceptor();
        interceptor.securityEnabled = true;
        interceptor.jwtValidationService = mock(JwtValidationService.class);

        CapturingCall call = capturingCall("grpc.health.v1.Health/Check");
        AtomicBoolean started = new AtomicBoolean();

        interceptor.interceptCall(call, new Metadata(), startRecording(started));

        assertThat(started).isTrue();
        assertThat(call.closeStatus).isNull();
    }

    @Test
    void rejectsMissingAuthorizationHeader() {
        OidcAuthInterceptor interceptor = new OidcAuthInterceptor();
        interceptor.securityEnabled = true;
        interceptor.jwtValidationService = mock(JwtValidationService.class);

        CapturingCall call = capturingCall("service/Method");

        interceptor.interceptCall(call, new Metadata(), startRecording(new AtomicBoolean()));

        assertThat(call.closeStatus).isNotNull();
        assertThat(call.closeStatus.getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(call.closeStatus.getDescription()).contains("Missing Bearer token");
    }

    @Test
    void rejectsInvalidToken() {
        OidcAuthInterceptor interceptor = new OidcAuthInterceptor();
        interceptor.securityEnabled = true;
        interceptor.jwtValidationService = mock(JwtValidationService.class);

        when(interceptor.jwtValidationService.validateTokenAsync(anyString()))
                .thenReturn(
                        Uni.createFrom()
                                .item(JwtValidationService.ValidationResult.failure("Invalid")));

        Metadata headers = new Metadata();
        headers.put(CapturingCall.AUTH_HEADER, "Bearer bad");
        CapturingCall call = capturingCall("service/Method");

        interceptor.interceptCall(call, headers, startRecording(new AtomicBoolean()));

        assertThat(call.closeStatus).isNotNull();
        assertThat(call.closeStatus.getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    void acceptsValidToken() {
        OidcAuthInterceptor interceptor = new OidcAuthInterceptor();
        interceptor.securityEnabled = true;
        interceptor.jwtValidationService = mock(JwtValidationService.class);

        when(interceptor.jwtValidationService.validateTokenAsync(anyString()))
                .thenReturn(
                        Uni.createFrom()
                                .item(
                                        JwtValidationService.ValidationResult.success(
                                                "user123", Set.of("admin"), null)));

        Metadata headers = new Metadata();
        headers.put(CapturingCall.AUTH_HEADER, "Bearer good");
        CapturingCall call = capturingCall("service/Method");
        AtomicBoolean started = new AtomicBoolean();

        interceptor.interceptCall(call, headers, startRecording(started));

        assertThat(started).isTrue();
        assertThat(call.closeStatus).isNull();
    }

    @Test
    void deferredListenerQueuesUntilDelegateSet() throws Exception {
        Object deferred = newDeferredListener();
        AtomicInteger messageCount = new AtomicInteger();
        AtomicInteger halfCloseCount = new AtomicInteger();

        @SuppressWarnings("unchecked")
        ServerCall.Listener<String> delegate =
                new ServerCall.Listener<>() {
                    @Override
                    public void onMessage(String message) {
                        messageCount.incrementAndGet();
                    }

                    @Override
                    public void onHalfClose() {
                        halfCloseCount.incrementAndGet();
                    }
                };

        invokeListener(deferred, "onMessage", "payload");
        invokeListener(deferred, "onHalfClose");

        setDelegate(deferred, delegate);

        assertThat(messageCount.get()).isEqualTo(1);
        assertThat(halfCloseCount.get()).isEqualTo(1);
    }

    @Test
    void deferredListenerRunsImmediatelyWhenDelegatePresent() throws Exception {
        Object deferred = newDeferredListener();
        AtomicInteger readyCount = new AtomicInteger();

        @SuppressWarnings("unchecked")
        ServerCall.Listener<String> delegate =
                new ServerCall.Listener<>() {
                    @Override
                    public void onReady() {
                        readyCount.incrementAndGet();
                    }
                };

        setDelegate(deferred, delegate);
        invokeListener(deferred, "onReady");

        assertThat(readyCount.get()).isEqualTo(1);
    }

    private ServerCallHandler<Empty, Empty> startRecording(AtomicBoolean started) {
        return (call, headers) -> {
            started.set(true);
            return new ServerCall.Listener<>() {};
        };
    }

    private CapturingCall capturingCall(String fullMethodName) {
        MethodDescriptor<Empty, Empty> descriptor =
                MethodDescriptor.<Empty, Empty>newBuilder()
                        .setType(MethodDescriptor.MethodType.UNARY)
                        .setFullMethodName(fullMethodName)
                        .setRequestMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance()))
                        .setResponseMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance()))
                        .build();
        return new CapturingCall(descriptor);
    }

    private static class CapturingCall extends ServerCall<Empty, Empty> {
        static final Metadata.Key<String> AUTH_HEADER =
                Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);

        private final MethodDescriptor<Empty, Empty> descriptor;
        Status closeStatus;

        CapturingCall(MethodDescriptor<Empty, Empty> descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public void request(int numMessages) {}

        @Override
        public void sendHeaders(Metadata headers) {}

        @Override
        public void sendMessage(Empty message) {}

        @Override
        public void close(Status status, Metadata trailers) {
            this.closeStatus = status;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public MethodDescriptor<Empty, Empty> getMethodDescriptor() {
            return descriptor;
        }
    }

    private Object newDeferredListener() throws Exception {
        Class<?> cls =
                Class.forName(
                        "com.ammann.entropy.interceptor.OidcAuthInterceptor$DeferredListener");
        var ctor = cls.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private void setDelegate(Object deferred, ServerCall.Listener<?> delegate) throws Exception {
        var method =
                deferred.getClass().getDeclaredMethod("setDelegate", ServerCall.Listener.class);
        method.setAccessible(true);
        method.invoke(deferred, delegate);
    }

    private void invokeListener(Object deferred, String methodName, Object... args)
            throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = Object.class;
        }
        var method = deferred.getClass().getMethod(methodName, types);
        method.invoke(deferred, args);
    }
}
