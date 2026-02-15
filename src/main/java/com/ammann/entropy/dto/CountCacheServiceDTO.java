/* (C)2026 */
package com.ammann.entropy.dto;

import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Abstraction layer for count query caching.
 *
 * <p>Currently uses Caffeine (in-memory), but designed for easy migration to Redis.</p>
 *
 * <p>Migration path:
 * <ol>
 *   <li>Add quarkus-redis-cache dependency</li>
 *   <li>Switch @CacheName to Redis-backed cache</li>
 *   <li>Update application.properties with Redis config</li>
 * </ol>
 * </p>
 */
@ApplicationScoped
public class CountCacheServiceDTO {

    private static final Logger LOG = Logger.getLogger(CountCacheServiceDTO.class);

    /**
     * Cache count result with configurable TTL.
     *
     * @param cacheKey Unique key based on query parameters
     * @param countSupplier Supplier that executes the actual count query
     * @return Cached or freshly computed count
     */
    @CacheResult(cacheName = "entity-counts")
    public long getCachedCount(String cacheKey, Supplier<Long> countSupplier) {
        LOG.debugf("Cache miss for key: %s, executing count query", cacheKey);
        return countSupplier.get();
    }

    /**
     * Generate cache key from query parameters.
     *
     * <p>Format: "entityName:filter1=value1:filter2=value2:..."</p>
     *
     * @param entityName The entity type being counted
     * @param params Key-value pairs of filter parameters (alternating key, value)
     * @return A unique cache key string
     */
    public static String generateCacheKey(String entityName, Object... params) {
        StringBuilder key = new StringBuilder(entityName);
        for (int i = 0; i < params.length; i += 2) {
            if (i + 1 < params.length && params[i + 1] != null) {
                key.append(":").append(params[i]).append("=").append(params[i + 1]);
            }
        }
        return key.toString();
    }
}
