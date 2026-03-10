package com.dataprocessing.ingestion;

import com.dataprocessing.domain.SensorEvent;

import java.util.List;
import java.util.Objects;

/**
 * Immutable unit of work placed on the IngestionQueue by POST /events.
 * Carries the validated account identity alongside the raw event list.
 */
public record IncomingBatch(
        String accountId,
        List<SensorEvent> events
) {
    public IncomingBatch {
        accountId = Objects.requireNonNull(accountId, "accountId");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
    }
}
