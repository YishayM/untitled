package com.dataprocessing.web;

import com.dataprocessing.cache.ServiceCache;
import com.dataprocessing.repository.ServiceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ServiceController {

    private final ServiceRepository serviceRepository;
    private final ServiceCache serviceCache;

    public ServiceController(ServiceRepository serviceRepository, ServiceCache serviceCache) {
        this.serviceRepository = serviceRepository;
        this.serviceCache = serviceCache;
    }

    @PutMapping("/services/{serviceName}")
    public ResponseEntity<Void> setVisibility(
            @PathVariable String serviceName,
            @RequestParam boolean isPublic,
            @RequestHeader("X-Account-ID") String accountId) {

        serviceRepository.upsert(accountId, serviceName, isPublic);
        serviceCache.put(accountId, serviceName, isPublic);
        return ResponseEntity.ok().build();
    }
}
