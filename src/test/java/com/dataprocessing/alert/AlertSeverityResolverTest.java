package com.dataprocessing.alert;

import com.dataprocessing.cache.ServiceCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AlertSeverityResolverTest {

    private ServiceCache serviceCache;
    private AlertSeverityResolver resolver;

    @BeforeEach
    void setUp() {
        serviceCache = new ServiceCache();
        resolver = new AlertSeverityResolver(serviceCache);
    }

    @Test
    void bothPrivate_returnsMedium() {
        assertThat(resolver.resolve("acct", "svc-a", "svc-b")).isEqualTo(Severity.MEDIUM);
    }

    @Test
    void sourcePublic_returnsHigh() {
        serviceCache.put("acct", "svc-a", true);
        assertThat(resolver.resolve("acct", "svc-a", "svc-b")).isEqualTo(Severity.HIGH);
    }

    @Test
    void destinationPublic_returnsHigh() {
        serviceCache.put("acct", "svc-b", true);
        assertThat(resolver.resolve("acct", "svc-a", "svc-b")).isEqualTo(Severity.HIGH);
    }

    @Test
    void bothPublic_returnsHigh() {
        serviceCache.put("acct", "svc-a", true);
        serviceCache.put("acct", "svc-b", true);
        assertThat(resolver.resolve("acct", "svc-a", "svc-b")).isEqualTo(Severity.HIGH);
    }

    @Test
    void publicFlagInDifferentAccount_doesNotElevate() {
        serviceCache.put("other-acct", "svc-b", true);
        assertThat(resolver.resolve("acct", "svc-a", "svc-b")).isEqualTo(Severity.MEDIUM);
    }
}
