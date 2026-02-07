/* (C)2026 */
package com.ammann.entropy.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Attributes;
import io.grpc.CallCredentials;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.SecurityLevel;
import io.grpc.Status;
import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class NistValidationServiceBearerTokenCallCredentialsTest {

    @Test
    void applyRequestMetadataAddsAuthorizationHeader() throws Exception {
        CallCredentials creds = newCredentials("token-123");

        AtomicReference<Metadata> applied = new AtomicReference<>();
        AtomicReference<Status> failed = new AtomicReference<>();

        CallCredentials.MetadataApplier applier =
                new CallCredentials.MetadataApplier() {
                    @Override
                    public void apply(Metadata headers) {
                        applied.set(headers);
                    }

                    @Override
                    public void fail(Status status) {
                        failed.set(status);
                    }
                };

        creds.applyRequestMetadata(requestInfo(), Runnable::run, applier);

        assertThat(failed.get()).isNull();
        assertThat(applied.get()).isNotNull();
        Metadata.Key<String> key =
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
        assertThat(applied.get().get(key)).isEqualTo("Bearer token-123");
    }

    @Test
    void applyRequestMetadataFailsWhenApplierThrows() throws Exception {
        CallCredentials creds = newCredentials("token-xyz");

        AtomicReference<Status> failed = new AtomicReference<>();

        CallCredentials.MetadataApplier applier =
                new CallCredentials.MetadataApplier() {
                    @Override
                    public void apply(Metadata headers) {
                        throw new RuntimeException("boom");
                    }

                    @Override
                    public void fail(Status status) {
                        failed.set(status);
                    }
                };

        creds.applyRequestMetadata(requestInfo(), Runnable::run, applier);

        assertThat(failed.get()).isNotNull();
        assertThat(failed.get().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    private static CallCredentials newCredentials(String token) throws Exception {
        Class<?> cls =
                Class.forName(
                        "com.ammann.entropy.service.NistValidationService$BearerTokenCallCredentials");
        Constructor<?> ctor = cls.getDeclaredConstructor(String.class);
        ctor.setAccessible(true);
        return (CallCredentials) ctor.newInstance(token);
    }

    private static CallCredentials.RequestInfo requestInfo() {
        return new CallCredentials.RequestInfo() {
            @Override
            public MethodDescriptor<?, ?> getMethodDescriptor() {
                return null;
            }

            @Override
            public SecurityLevel getSecurityLevel() {
                return SecurityLevel.NONE;
            }

            @Override
            public String getAuthority() {
                return "localhost";
            }

            @Override
            public Attributes getTransportAttrs() {
                return Attributes.EMPTY;
            }
        };
    }
}
