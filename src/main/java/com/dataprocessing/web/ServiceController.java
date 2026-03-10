package com.dataprocessing.web;

import com.dataprocessing.cache.ServiceCache;
import com.dataprocessing.repository.ServiceRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ServiceController {

    private static final Logger log = LoggerFactory.getLogger(ServiceController.class);

    private final ServiceRepository serviceRepository;
    private final ServiceCache serviceCache;

    public ServiceController(ServiceRepository serviceRepository, ServiceCache serviceCache) {
        this.serviceRepository = serviceRepository;
        this.serviceCache = serviceCache;
    }

    @PutMapping("/services/{serviceName}")
    public ResponseEntity<Void> setVisibility(
            @PathVariable String serviceName,
            @RequestParam("public") boolean isPublic,
            HttpServletRequest request) {

        String accountId = (String) request.getAttribute("accountId");

        serviceRepository.upsert(accountId, serviceName, isPublic);
        try {
            serviceCache.put(accountId, serviceName, isPublic);
        } catch (Exception e) {
            log.warn("Cache update failed after DB upsert for account={} service={}; " +
                    "cache will be corrected on next restart/warmup", accountId, serviceName, e);
        }
        return ResponseEntity.noContent().build();
    }
}
