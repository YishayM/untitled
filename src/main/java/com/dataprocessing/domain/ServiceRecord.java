package com.dataprocessing.domain;

public record ServiceRecord(
        String accountId,
        String serviceName,
        boolean isPublic
) {}
