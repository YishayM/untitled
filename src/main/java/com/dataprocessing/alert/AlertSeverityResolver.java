package com.dataprocessing.alert;

import com.dataprocessing.cache.ServiceCache;
import org.springframework.stereotype.Component;

/**
 * Resolves alert severity for a (source, destination) pair within an account.
 * HIGH if either endpoint is marked public; MEDIUM otherwise.
 */
@Component
public class AlertSeverityResolver {

    private final ServiceCache serviceCache;

    public AlertSeverityResolver(ServiceCache serviceCache) {
        this.serviceCache = serviceCache;
    }

    public Severity resolve(String accountId, String source, String destination) {
        if (serviceCache.isPublic(accountId, source) ||
            serviceCache.isPublic(accountId, destination)) {
            return Severity.HIGH;
        }
        return Severity.MEDIUM;
    }
}
