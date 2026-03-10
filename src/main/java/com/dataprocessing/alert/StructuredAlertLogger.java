package com.dataprocessing.alert;

import com.dataprocessing.domain.SensitiveClassification;

import java.time.Instant;

/**
 * Emits a structured alert log line.
 * Injectable so implementations can be swapped and unit tests can assert exact calls.
 */
public interface StructuredAlertLogger {

    /**
     * Emits one JSON log line for a novel sensitive data flow.
     *
     * @param accountId      owning account
     * @param source         originating service
     * @param destination    receiving service
     * @param classification the sensitive data type that triggered the alert
     * @param severity       MEDIUM or HIGH
     * @param detectedAt     wall-clock time of detection
     */
    void logAlert(String accountId, String source, String destination,
                  SensitiveClassification classification, Severity severity,
                  Instant detectedAt);
}
