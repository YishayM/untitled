package com.dataprocessing.cache;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store of service public/private flags, scoped per account.
 *
 * Key format: "accountId:serviceName"
 *
 * Thread-safe: ConcurrentHashMap provides lock-free reads.
 * Invalidation: PUT /services writes through to this cache synchronously,
 * so the cache is at most milliseconds stale after an update.
 *
 * Not durable across restarts — warmed from DB on startup via ServiceCacheWarmup.
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

    private String key(String accountId, String serviceName) {
        return accountId + ":" + serviceName;
    }
}
