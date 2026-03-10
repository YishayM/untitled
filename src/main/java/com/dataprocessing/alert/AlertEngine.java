package com.dataprocessing.alert;

import com.dataprocessing.domain.SensorEvent;

/**
 * Evaluates a single sensor event for novelty and emits a structured alert log line
 * exactly once per novel (accountId, source, destination, classification) triple.
 *
 * Implemented in PR4. The no-op stub is active until then.
 */
public interface AlertEngine {

    /**
     * Processes one event: checks cache + DB, resolves severity, logs alert if novel.
     *
     * @param accountId the owning account, never null or blank
     * @param event     the sensor event to evaluate, never null
     */
    void process(String accountId, SensorEvent event);
}
