package com.dataprocessing.ingestion;

import com.dataprocessing.domain.SensorEvent;

import java.util.List;

/**
 * Immutable unit of work placed on the IngestionQueue by POST /events.
 * Carries the validated account identity alongside the raw event list.
 */
public record IncomingBatch(
        String accountId,
        List<SensorEvent> events
) {}
