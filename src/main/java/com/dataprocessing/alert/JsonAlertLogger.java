package com.dataprocessing.alert;

import com.dataprocessing.domain.SensitiveClassification;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Emits one JSON log line per alert to stdout via logstash-logback-encoder.
 * All fields appear as top-level JSON keys, making the output grep- and
 * pipeline-friendly without further parsing.
 *
 * Example output:
 * {"type":"SECURITY_ALERT","account_id":"acct-1","source":"users",
 *  "destination":"payment","classification":"CREDIT_CARD_NUMBER",
 *  "severity":"HIGH","detected_at":"2026-03-10T10:00:00Z"}
 */
@Component
public class JsonAlertLogger implements StructuredAlertLogger {

    private static final Logger log = LoggerFactory.getLogger(JsonAlertLogger.class);

    @Override
    public void logAlert(String accountId, String source, String destination,
                         SensitiveClassification classification, Severity severity,
                         Instant detectedAt) {
        log.warn("SECURITY_ALERT",
                StructuredArguments.kv("type",           "SECURITY_ALERT"),
                StructuredArguments.kv("account_id",     accountId),
                StructuredArguments.kv("source",         source),
                StructuredArguments.kv("destination",    destination),
                StructuredArguments.kv("classification", classification.name()),
                StructuredArguments.kv("severity",       severity.name()),
                StructuredArguments.kv("detected_at",    detectedAt.toString()));
    }
}
