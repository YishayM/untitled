package com.dataprocessing.alert;

import com.dataprocessing.cache.NoveltyCache;
import com.dataprocessing.domain.SensitiveClassification;
import com.dataprocessing.domain.SensorEvent;
import com.dataprocessing.domain.TripleKey;
import com.dataprocessing.repository.SeenTriplesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SensitiveDataAlertEngineTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-03-10T10:00:00Z");

    @Mock NoveltyCache noveltyCache;
    @Mock SeenTriplesRepository seenTriplesRepository;
    @Mock AlertSeverityResolver severityResolver;
    @Mock StructuredAlertLogger alertLogger;

    private SensitiveDataAlertEngine engine;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        engine = new SensitiveDataAlertEngine(
                noveltyCache, seenTriplesRepository, severityResolver, alertLogger, fixedClock);
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void novelSensitiveTriple_logsAlertWithDetectionTimestamp() {
        var event = new SensorEvent("ts", "users", "payment",
                Map.of("firstName", "FIRST_NAME"));

        when(noveltyCache.contains(any())).thenReturn(false);
        when(seenTriplesRepository.recordIfAbsent(any())).thenReturn(true);
        when(severityResolver.resolve(any(), any(), any())).thenReturn(Severity.MEDIUM);

        engine.process("acct-1", event);

        // Verify the exact detection timestamp from the injected fixed clock
        verify(alertLogger).logAlert(
                eq("acct-1"), eq("users"), eq("payment"),
                eq(SensitiveClassification.FIRST_NAME), eq(Severity.MEDIUM), eq(FIXED_NOW));
        verify(noveltyCache).markSeen(any());
    }

    // -------------------------------------------------------------------------
    // Cache fast-path
    // -------------------------------------------------------------------------

    @Test
    void cacheHit_skipsDbAndLogger() {
        var event = new SensorEvent("ts", "users", "payment",
                Map.of("firstName", "FIRST_NAME"));

        when(noveltyCache.contains(any())).thenReturn(true);

        engine.process("acct-1", event);

        verify(seenTriplesRepository, never()).recordIfAbsent(any());
        verify(alertLogger, never()).logAlert(any(), any(), any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // DB conflict — another thread/node won the race
    // -------------------------------------------------------------------------

    @Test
    void dbConflict_warmsLocalCacheAndSkipsLogger() {
        var event = new SensorEvent("ts", "users", "payment",
                Map.of("firstName", "FIRST_NAME"));

        when(noveltyCache.contains(any())).thenReturn(false);
        when(seenTriplesRepository.recordIfAbsent(any())).thenReturn(false);

        engine.process("acct-1", event);

        // Cache is always warmed after the DB decision — symmetric across both branches
        verify(noveltyCache).markSeen(any());
        verify(alertLogger, never()).logAlert(any(), any(), any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // Non-sensitive classification — skip entirely
    // -------------------------------------------------------------------------

    @Test
    void nonSensitiveClassification_skipsEverything() {
        // Use strings that are clearly not classification names
        var event = new SensorEvent("ts", "a", "b",
                Map.of("x", "not-a-classification", "y", "IGNORED"));

        engine.process("acct-1", event);

        verify(noveltyCache, never()).contains(any());
        verify(seenTriplesRepository, never()).recordIfAbsent(any());
        verify(alertLogger, never()).logAlert(any(), any(), any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // Mixed values — only sensitive keys alert
    // -------------------------------------------------------------------------

    @Test
    void mixedValues_onlySensitiveKeysAlert() {
        var event = new SensorEvent("ts", "a", "b", Map.of(
                "firstName", "FIRST_NAME",              // sensitive
                "ssn",       "SOCIAL_SECURITY_NUMBER",  // sensitive
                "price",     "not-a-classification"     // not sensitive
        ));

        when(noveltyCache.contains(any())).thenReturn(false);
        when(seenTriplesRepository.recordIfAbsent(any())).thenReturn(true);
        when(severityResolver.resolve(any(), any(), any())).thenReturn(Severity.MEDIUM);

        engine.process("acct-1", event);

        verify(alertLogger, times(2)).logAlert(any(), any(), any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // Correct TripleKey scoping
    // -------------------------------------------------------------------------

    @Test
    void tripleKey_includesAccountId() {
        var event = new SensorEvent("ts", "src", "dst",
                Map.of("cc", "CREDIT_CARD_NUMBER"));

        when(noveltyCache.contains(any())).thenReturn(false);
        when(seenTriplesRepository.recordIfAbsent(any())).thenReturn(true);
        when(severityResolver.resolve(any(), any(), any())).thenReturn(Severity.HIGH);

        engine.process("acct-99", event);

        var expectedTriple = new TripleKey("acct-99", "src", "dst",
                SensitiveClassification.CREDIT_CARD_NUMBER);
        verify(seenTriplesRepository).recordIfAbsent(expectedTriple);
    }
}
