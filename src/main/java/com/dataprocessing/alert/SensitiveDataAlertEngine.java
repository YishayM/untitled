package com.dataprocessing.alert;

import com.dataprocessing.cache.NoveltyCache;
import com.dataprocessing.domain.SensitiveClassification;
import com.dataprocessing.domain.SensorEvent;
import com.dataprocessing.domain.TripleKey;
import com.dataprocessing.repository.SeenTriplesRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Real AlertEngine implementation. Named "sensitiveDataAlertEngine" so that
 * NoOpAlertEngine's @ConditionalOnMissingBean(AlertEngine.class) correctly
 * deactivates when this bean is present.
 *
 * Processing path per event value:
 *   1. SensitiveClassification.of() → empty = skip (not sensitive)
 *   2. NoveltyCache.contains()      → hit  = skip (fast path, no DB)
 *   3. SeenTriplesRepository.recordIfAbsent() using ON CONFLICT DO NOTHING
 *      → false (conflict) = another thread/node won; warm cache and skip
 *      → true  (inserted) = first time seen; resolve severity + log alert + warm cache
 */
@Component("sensitiveDataAlertEngine")
public class SensitiveDataAlertEngine implements AlertEngine {

    private final NoveltyCache noveltyCache;
    private final SeenTriplesRepository seenTriplesRepository;
    private final AlertSeverityResolver severityResolver;
    private final StructuredAlertLogger alertLogger;

    public SensitiveDataAlertEngine(NoveltyCache noveltyCache,
                                    SeenTriplesRepository seenTriplesRepository,
                                    AlertSeverityResolver severityResolver,
                                    StructuredAlertLogger alertLogger) {
        this.noveltyCache = noveltyCache;
        this.seenTriplesRepository = seenTriplesRepository;
        this.severityResolver = severityResolver;
        this.alertLogger = alertLogger;
    }

    @Override
    public void process(String accountId, SensorEvent event) {
        for (var entry : event.values().entrySet()) {
            SensitiveClassification.of(entry.getValue())
                    .ifPresent(classification -> processTriple(
                            new TripleKey(accountId, event.source(), event.destination(), classification)
                    ));
        }
    }

    private void processTriple(TripleKey triple) {
        if (noveltyCache.contains(triple)) {
            return; // fast path — already processed
        }

        boolean inserted = seenTriplesRepository.recordIfAbsent(triple);
        if (!inserted) {
            // DB conflict: another thread or node inserted first.
            // Warm the local cache so subsequent duplicates hit the fast path.
            noveltyCache.markSeen(triple);
            return;
        }

        Severity severity = severityResolver.resolve(
                triple.accountId(), triple.source(), triple.destination());

        alertLogger.logAlert(
                triple.accountId(), triple.source(), triple.destination(),
                triple.classification(), severity, Instant.now());

        noveltyCache.markSeen(triple);
    }
}
