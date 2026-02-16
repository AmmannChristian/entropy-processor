/* (C)2026 */
package com.ammann.entropy.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;

/**
 * CDI producer for creating named ManagedExecutor instances.
 *
 * <p>Provides the "nist-validation-executor" bean used by NistValidationService
 * for async NIST validation job processing. The executor is configured via
 * application.properties with dedicated thread pool settings.
 */
@ApplicationScoped
public class ExecutorProducer {

    /**
     * Produces a named ManagedExecutor for NIST validation jobs.
     *
     * <p>Configuration properties:
     * <ul>
     *   <li>quarkus.thread-pool.nist-validation-executor.max-threads</li>
     *   <li>quarkus.thread-pool.nist-validation-executor.core-threads</li>
     *   <li>quarkus.thread-pool.nist-validation-executor.queue-size</li>
     * </ul>
     *
     * @return Configured ManagedExecutor instance
     */
    @Produces
    @Named("nist-validation-executor")
    @ApplicationScoped
    public ManagedExecutor createNistExecutor() {
        return ManagedExecutor.builder()
                .maxAsync(2) // Match NIST_EXECUTOR_MAX_THREADS default
                .maxQueued(10) // Match NIST_EXECUTOR_QUEUE_SIZE default
                .propagated(ThreadContext.ALL_REMAINING)
                .cleared(ThreadContext.TRANSACTION)
                .build();
    }
}
