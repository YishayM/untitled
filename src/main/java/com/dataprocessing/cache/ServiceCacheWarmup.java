package com.dataprocessing.cache;

import com.dataprocessing.repository.ServiceRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Warms the ServiceCache from DB on startup so the first alerts after restart
 * correctly resolve severity without a DB round-trip.
 */
@Component
public class ServiceCacheWarmup {

    private final ServiceRepository serviceRepository;
    private final ServiceCache serviceCache;

    public ServiceCacheWarmup(ServiceRepository serviceRepository, ServiceCache serviceCache) {
        this.serviceRepository = serviceRepository;
        this.serviceCache = serviceCache;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        serviceRepository.findAll()
                .forEach(s -> serviceCache.put(s.accountId(), s.serviceName(), s.isPublic()));
    }
}
