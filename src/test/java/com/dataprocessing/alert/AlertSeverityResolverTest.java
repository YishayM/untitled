package com.dataprocessing.alert;

import com.dataprocessing.cache.ServiceCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertSeverityResolverTest {

    @Mock
    private ServiceCache serviceCache;

    private AlertSeverityResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AlertSeverityResolver(serviceCache);
    }

    @Test
    void bothPrivate_returnsMedium() {
        when(serviceCache.isPublic("acct", "svc-a")).thenReturn(false);
        when(serviceCache.isPublic("acct", "svc-b")).thenReturn(false);
        assertThat(resolver.resolve("acct", "svc-a", "svc-b")).isEqualTo(Severity.MEDIUM);
    }

    @Test
    void sourcePublic_returnsHigh() {
        when(serviceCache.isPublic("acct", "svc-a")).thenReturn(true);
        assertThat(resolver.resolve("acct", "svc-a", "svc-b")).isEqualTo(Severity.HIGH);
    }

    @Test
    void destinationPublic_returnsHigh() {
        when(serviceCache.isPublic("acct", "svc-a")).thenReturn(false);
        when(serviceCache.isPublic("acct", "svc-b")).thenReturn(true);
        assertThat(resolver.resolve("acct", "svc-a", "svc-b")).isEqualTo(Severity.HIGH);
    }

    @Test
    void bothPublic_returnsHigh() {
        when(serviceCache.isPublic("acct", "svc-a")).thenReturn(true);
        assertThat(resolver.resolve("acct", "svc-a", "svc-b")).isEqualTo(Severity.HIGH);
    }

    @Test
    void publicFlagInDifferentAccount_doesNotElevate() {
        when(serviceCache.isPublic("acct", "svc-a")).thenReturn(false);
        when(serviceCache.isPublic("acct", "svc-b")).thenReturn(false);
        assertThat(resolver.resolve("acct", "svc-a", "svc-b")).isEqualTo(Severity.MEDIUM);
    }
}
