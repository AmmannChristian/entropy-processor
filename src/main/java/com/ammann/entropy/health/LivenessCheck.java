package com.ammann.entropy.health;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/**
 * Liveness health check that unconditionally reports the application as alive.
 *
 * <p>Used by container orchestrators to detect unresponsive processes.
 * A failure to respond indicates that the JVM is hung or the application
 * has entered an unrecoverable state.
 */
@Liveness
public class LivenessCheck implements HealthCheck
{

    @Override
    public HealthCheckResponse call()
    {
        return HealthCheckResponse.up("alive");
    }

}