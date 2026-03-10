package com.dataprocessing.alert;

import com.dataprocessing.cache.NoveltyCache;
import com.dataprocessing.domain.SensitiveClassification;
import com.dataprocessing.domain.SensorEvent;
import com.dataprocessing.domain.TripleKey;
import com.dataprocessing.repository.SeenTriplesRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
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
 *   4. NoveltyCache.markSeen()      → always called after DB decision (symmetric)
 *      → false (conflict) = another thread/node won; cache already warmed, skip
 *      → true  (inserted) = first time seen; resolve severity + log alert
 *
 * Timestamp: stamped at the start of processTriple (before DB latency) to
 * reflect when the event was detected, not when the alert was emitted.
 *
 * Clock is injected so tests can control time and assert exact detectedAt values.
 */
@Component("sensitiveDataAlertEngine")
public class SensitiveDataAlertEngine implements AlertEngine {

    private static final Logger log = LoggerFactory.getLogger(SensitiveDataAlertEngine.class);

    private final NoveltyCache noveltyCache;
    private final SeenTriplesRepository seenTriplesRepository;
    private final AlertSeverityResolver severityResolver;
    private final StructuredAlertLogger alertLogger;
    private final Clock clock;

    public SensitiveDataAlertEngine(NoveltyCache noveltyCache,
                                    SeenTriplesRepository seenTriplesRepository,
                                    AlertSeverityResolver severityResolver,
                                    StructuredAlertLogger alertLogger,
                                    Clock clock) {
        this.noveltyCache = noveltyCache;
        this.seenTriplesRepository = seenTriplesRepository;
        this.severityResolver = severityResolver;
        this.alertLogger = alertLogger;
        this.clock = clock;
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
        // Stamp at detection entry — before DB latency — so the timestamp reflects
        // when the sensitive flow was observed, not when the alert was written.
        Instant detectedAt = clock.instant();

        if (noveltyCache.contains(triple)) {
            log.debug("cache hit, skipping account={} source={} destination={} classification={}",
                    triple.accountId(), triple.source(), triple.destination(), triple.classification());
            return;
        }

        boolean inserted = seenTriplesRepository.recordIfAbsent(triple);

        // Always warm the cache after the DB decision — symmetric for both branches.
        // On the conflict path this prevents repeated DB round-trips for the same triple.
        // On the inserted path this is the normal warm-up.
        noveltyCache.markSeen(triple);

        if (!inserted) {
            log.debug("DB conflict (another node inserted first), cache warmed account={} source={} destination={}",
                    triple.accountId(), triple.source(), triple.destination());
            return;
        }

        Severity severity = severityResolver.resolve(
                triple.accountId(), triple.source(), triple.destination());

        alertLogger.logAlert(
                triple.accountId(), triple.source(), triple.destination(),
                triple.classification(), severity, detectedAt);

        log.debug("novel triple recorded account={} source={} destination={} classification={} severity={}",
                triple.accountId(), triple.source(), triple.destination(),
                triple.classification(), severity);
    }
}
