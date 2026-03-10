package com.dataprocessing.cache;

import com.dataprocessing.repository.ServiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Warms the ServiceCache from DB on startup so the first alerts after restart
 * correctly resolve severity without a DB round-trip.
 */
@Component
public class ServiceCacheWarmup {

    private static final Logger log = LoggerFactory.getLogger(ServiceCacheWarmup.class);

    private final ServiceRepository serviceRepository;
    private final ServiceCache serviceCache;

    public ServiceCacheWarmup(ServiceRepository serviceRepository, ServiceCache serviceCache) {
        this.serviceRepository = serviceRepository;
        this.serviceCache = serviceCache;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        log.info("ServiceCache warmup starting");
        try {
            var services = serviceRepository.findAll();
            // putIfAbsent prevents overwriting a concurrent PUT /services write that
            // raced with findAll() returning a point-in-time snapshot.
            services.forEach(s -> serviceCache.putIfAbsent(s.accountId(), s.serviceName(), s.isPublic()));
            log.info("ServiceCache warmup complete, loaded {} services", services.size());
        } catch (DataAccessException e) {
            log.error("ServiceCache warmup failed — cache will be empty until next restart. " +
                    "An empty services table on first boot is valid; " +
                    "otherwise check DB connectivity and schema initialisation.", e);
        }
    }
}
