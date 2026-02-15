/* (C)2026 */
package com.ammann.entropy.properties;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Centralized registry of REST API path constants used across all JAX-RS resources.
 *
 * <p>Organizes endpoints by functional area (entropy, events, system, admin, health)
 * to ensure consistent path naming and simplify path refactoring.
 */
@RegisterForReflection
public final class ApiProperties {

    private ApiProperties() {}

    /** Base path for API version 1. */
    public static final String BASE_URL_V1 = "/api/v1";

    /** Base path for API version 2. */
    public static final String BASE_URL_V2 = "/api/v2";

    /** CSV export endpoint path. */
    public static final String EXPORT_CSV = "/export/csv";

    /**
     * Entropy calculation endpoints
     */
    public static final class Entropy {
        private Entropy() {}

        public static final String BASE = "/entropy";
        public static final String SHANNON = BASE + "/shannon";
        public static final String COMPREHENSIVE = BASE + "/comprehensive";
        public static final String WINDOW = BASE + "/window";
        public static final String RENYI = BASE + "/renyi";
        public static final String NIST_LATEST = BASE + "/nist/latest";
        public static final String NIST_VALIDATE = BASE + "/nist/validate";
    }

    /**
     * Event data endpoints
     */
    public static final class Events {
        private Events() {}

        public static final String BASE = "/events";
        public static final String RECENT = BASE + "/recent";
        public static final String INTERVALS = BASE + "/intervals";
        public static final String STATISTICS = BASE + "/statistics";
        public static final String COUNT = BASE + "/count";
        public static final String QUALITY = BASE + "/quality";
        public static final String RATE = BASE + "/rate";
        public static final String INTERVAL_HISTOGRAM = BASE + "/interval-histogram";
    }

    /**
     * Public unauthenticated endpoints
     */
    public static final class Public {
        private Public() {}

        public static final String BASE = "/public";
        public static final String RECENT_ACTIVITY = BASE + "/recent-activity";
    }

    /**
     * System monitoring endpoints
     */
    public static final class System {
        private System() {}

        public static final String BASE = "/system";
        public static final String STATUS = BASE + "/status";
        public static final String CONFIG = BASE + "/config";
    }

    /**
     * Administrative endpoints
     */
    public static final class Admin {
        private Admin() {}

        public static final String BASE = "/admin";
        public static final String MAINTENANCE = BASE + "/maintenance";
    }

    /**
     * Health check endpoints (Quarkus defaults)
     */
    public static final class Health {
        private Health() {}

        public static final String BASE = "/q/health";
        public static final String LIVE = BASE + "/live";
        public static final String READY = BASE + "/ready";
        public static final String METRICS = "/q/metrics";
        public static final String OPENAPI = "/q/openapi";
    }
}
