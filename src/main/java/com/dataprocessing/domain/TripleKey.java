package com.dataprocessing.domain;

public record TripleKey(
        String accountId,
        String source,
        String destination,
        SensitiveClassification classification
) {}
