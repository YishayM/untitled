package com.dataprocessing.repository;

import com.dataprocessing.domain.ServiceRecord;

import java.util.List;

public interface ServiceRepository {

    /** Upsert a service's public flag for the given account. */
    void upsert(String accountId, String serviceName, boolean isPublic);

    /** Return all services across all accounts — used for cache warm-up on startup. */
    List<ServiceRecord> findAll();
}
