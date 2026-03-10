package com.dataprocessing.cache;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store of service public/private flags, scoped per account.
 *
 * Key format: "accountId\0serviceName" — NUL delimiter prevents collision when
 * accountId or serviceName contains a colon.
 *
 * Thread-safe: ConcurrentHashMap provides lock-free reads.
 * Invalidation: PUT /services writes through to this cache synchronously,
 * so the cache is at most milliseconds stale after an update.
 *
 * Not durable across restarts — warmed from DB on startup via ServiceCacheWarmup.
 *
 * Size bound: This cache is intentionally backed by a plain ConcurrentHashMap rather
 * than a Caffeine cache. The services table is bounded by the number of distinct
 * (account_id, service_name) pairs, which is expected to be small in practice.
 * Caffeine eviction would silently drop entries and cause stale miss-as-private
 * reads in the alert engine — incorrect behaviour that is worse than an OOM.
 * If the services table grows unboundedly, the correct fix is to add a DB-side
 * limit on service registrations per account, not to evict cache entries silently.
 */
@Component
public class ServiceCache {

    private final ConcurrentHashMap<String, Boolean> store = new ConcurrentHashMap<>();

    public void put(String accountId, String serviceName, boolean isPublic) {
        store.put(key(accountId, serviceName), isPublic);
    }

    public boolean isPublic(String accountId, String serviceName) {
        return store.getOrDefault(key(accountId, serviceName), false);
    }

    /** Puts the entry only if no entry already exists for this key. Used by warmup to avoid overwriting concurrent writes. */
    public void putIfAbsent(String accountId, String serviceName, boolean isPublic) {
        store.putIfAbsent(key(accountId, serviceName), isPublic);
    }

    private String key(String accountId, String serviceName) {
        return accountId + "\0" + serviceName;
    }
}
