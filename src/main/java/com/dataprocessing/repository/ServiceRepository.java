package com.dataprocessing.repository;

import com.dataprocessing.domain.ServiceRecord;

import java.util.List;

public interface ServiceRepository {

    /** Upsert a service's public flag for the given account. */
    void upsert(String accountId, String serviceName, boolean isPublic);

    /**
     * Return all services across all accounts — used for cache warm-up on startup.
     *
     * <p>Precondition: the {@code services} table must exist (i.e., schema.sql has executed).
     * If called before schema initialisation, throws {@link org.springframework.dao.DataAccessException}.
     * An empty result list is a valid first-boot state when no services have been registered yet.
     */
    List<ServiceRecord> findAll();
}
