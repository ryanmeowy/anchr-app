package com.anchr.core.integration.ai.client;

import com.anchr.core.settings.domain.model.CapabilityConfig;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * In-memory cache of resolved AI capability clients, keyed by slot
 * ({@code GENERATION}, {@code RERANK}, {@code EMBEDDING}).
 *
 * <p>Each entry pairs the built client with the {@link CapabilityConfig} it was built
 * from, so adapters can read {@code modelName}/{@code extraConfig} on a cache hit
 * without re-querying the database.
 *
 * <p>Reads ({@link #getOrBuild}) return the cached entry on hit, building only on miss.
 * Writes ({@link #put}) are invoked by the config service on every config mutation
 * (write-through) so the cached client is refreshed immediately after the DB change —
 * no TTL, no version column. {@link ConcurrentHashMap} guarantees atomic per-entry
 * visibility for concurrent readers.
 */
@Component
public class ClientCacheManager {

    public record ResolvedClient(Object client, CapabilityConfig config) {}

    private final ConcurrentHashMap<String, ResolvedClient> cache = new ConcurrentHashMap<>();

    public ResolvedClient getOrBuild(String slot, Supplier<ResolvedClient> builder) {
        ResolvedClient existing = cache.get(slot);
        if (existing != null) {
            return existing;
        }
        ResolvedClient built = builder.get();
        ResolvedClient prev = cache.putIfAbsent(slot, built);
        return prev != null ? prev : built;
    }

    public void put(String slot, ResolvedClient entry) {
        cache.put(slot, entry);
    }

    public void invalidate(String slot) {
        cache.remove(slot);
    }
}
