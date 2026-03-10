package com.dataprocessing.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceCacheTest {

    private ServiceCache cache;

    @BeforeEach
    void setUp() {
        cache = new ServiceCache();
    }

    @Test
    void unknownService_isNotPublic() {
        assertThat(cache.isPublic("acct", "unknown-svc")).isFalse();
    }

    @Test
    void afterPut_isPublicReflectsValue() {
        cache.put("acct", "svc", true);
        assertThat(cache.isPublic("acct", "svc")).isTrue();

        cache.put("acct", "svc", false);
        assertThat(cache.isPublic("acct", "svc")).isFalse();
    }

    @Test
    void differentAccounts_areIsolated() {
        cache.put("acct-1", "svc", true);
        assertThat(cache.isPublic("acct-2", "svc")).isFalse();
    }

    @Test
    void eitherEndpointPublic_isDetected() {
        cache.put("acct", "svc-a", false);
        cache.put("acct", "svc-b", true);

        assertThat(cache.isPublic("acct", "svc-a")).isFalse();
        assertThat(cache.isPublic("acct", "svc-b")).isTrue();
    }
}
