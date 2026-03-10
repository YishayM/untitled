package com.dataprocessing.cache;

import com.dataprocessing.domain.TripleKey;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

/**
 * Caffeine-backed NoveltyCache bounded at 100,000 entries.
 *
 * Bounded to prevent OOM under adversarial workloads. LRU eviction means
 * an evicted entry falls back to the DB on next occurrence — still correct,
 * just costs one extra DB round-trip.
 */
@Component
public class CaffeineNoveltyCache implements NoveltyCache {

    private final Cache<TripleKey, Boolean> store = Caffeine.newBuilder()
            .maximumSize(100_000)
            .recordStats()  // exposes hit rate, eviction count — observable via CacheStats
            .build();

    @Override
    public boolean contains(TripleKey triple) {
        return store.getIfPresent(triple) != null;
    }

    @Override
    public void markSeen(TripleKey triple) {
        store.put(triple, Boolean.TRUE);
    }
}
