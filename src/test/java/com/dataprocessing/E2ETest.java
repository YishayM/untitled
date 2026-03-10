package com.dataprocessing;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests. All scenarios are written upfront (TDD).
 * These tests fail until each feature is implemented in subsequent PRs.
 *
 * Isolation strategy: each test generates a unique accountId so that
 * DB rows and log lines from one test never bleed into another,
 * regardless of execution order or async timing.
 *
 * Scenarios:
 *   1. Sensitive triple → MEDIUM alert logged exactly once
 *   2. Public service involved → HIGH alert logged
 *   3. Duplicate events → exactly one alert (not two)
 *   4. Non-sensitive classifications (DATE, NUMBER) → no alert
 *   5. Service toggled back to private → MEDIUM alert
 *   6. GET /graph → vis.js-compatible nodes + edges
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class E2ETest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    // Unique per test — prevents row contamination and log-capture cross-pollution
    private String accountId;

    @BeforeEach
    void setUp() {
        accountId = "acct-" + UUID.randomUUID();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Account-ID", accountId);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private Map<String, Object> event(String source, String destination, Map<String, String> values) {
        return Map.of(
                "date", "1610293274000",
                "source", source,
                "destination", destination,
                "values", values
        );
    }

    private ResponseEntity<Void> postEvents(List<Map<String, Object>> events) {
        return rest.postForEntity(
                "/events",
                new HttpEntity<>(events, headers()),
                Void.class
        );
    }

    private void markPublic(String serviceName, boolean isPublic) {
        rest.exchange(
                "/services/" + serviceName + "?public=" + isPublic,
                HttpMethod.PUT,
                new HttpEntity<>(null, headers()),
                Void.class
        );
    }

    private void awaitAlert(CapturedOutput output, String classification) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(output.toString())
                        .contains("SECURITY_ALERT")
                        .contains(accountId)
                        .contains(classification));
    }

    private long countAlerts(CapturedOutput output, String source, String destination, String classification) {
        return output.toString().lines()
                .filter(line -> line.contains("SECURITY_ALERT")
                        && line.contains(accountId)
                        && line.contains(source)
                        && line.contains(destination)
                        && line.contains(classification))
                .count();
    }

    // -------------------------------------------------------------------------
    // Test 1: new sensitive triple → MEDIUM alert
    // -------------------------------------------------------------------------

    @Test
    void newSensitiveTriple_logsMediumAlert(CapturedOutput output) {
        ResponseEntity<Void> response = postEvents(List.of(
                event("users", "payment", Map.of(
                        "firstName", "FIRST_NAME",
                        "price",     "NUMBER"   // non-sensitive — must not alert
                ))
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        awaitAlert(output, "FIRST_NAME");

        assertThat(output.toString())
                .contains("SECURITY_ALERT")
                .contains("FIRST_NAME")
                .contains("users")
                .contains("payment")
                .contains("MEDIUM")
                .doesNotContain("HIGH");
    }

    // -------------------------------------------------------------------------
    // Test 2: public service → HIGH alert
    // -------------------------------------------------------------------------

    @Test
    void newSensitiveTriple_withPublicDestination_logsHighAlert(CapturedOutput output) {
        markPublic("stripe.com", true);

        postEvents(List.of(
                event("payment", "stripe.com", Map.of("credit_card", "CREDIT_CARD_NUMBER"))
        ));

        awaitAlert(output, "CREDIT_CARD_NUMBER");

        assertThat(output.toString())
                .contains("SECURITY_ALERT")
                .contains("CREDIT_CARD_NUMBER")
                .contains("payment")
                .contains("stripe.com")
                .contains("HIGH");
    }

    // -------------------------------------------------------------------------
    // Test 3: exactly-once — three identical events → one alert
    // -------------------------------------------------------------------------

    @Test
    void duplicateEvents_fireAlertExactlyOnce(CapturedOutput output) {
        var events = List.of(
                event("auth", "db", Map.of("ssn", "SOCIAL_SECURITY_NUMBER"))
        );

        postEvents(events);
        postEvents(events);
        postEvents(events);

        awaitAlert(output, "SOCIAL_SECURITY_NUMBER");

        // Drain async workers: wait until seen_triples row count is stable
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    int rows = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM seen_triples WHERE account_id = ?",
                            Integer.class, accountId);
                    assertThat(rows).isEqualTo(1);
                });

        long count = countAlerts(output, "auth", "db", "SOCIAL_SECURITY_NUMBER");
        assertThat(count).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Test 4: non-sensitive types → no alert
    // Assertion uses DB state (seen_triples empty) as the completion signal
    // instead of Thread.sleep, to avoid vacuous "not yet run" passes.
    // -------------------------------------------------------------------------

    @Test
    void nonSensitiveClassifications_doNotFireAlert(CapturedOutput output) {
        postEvents(List.of(
                event("analytics", "warehouse", Map.of(
                        "count",     "NUMBER",
                        "eventDate", "DATE"
                ))
        ));

        // Poll until worker has run: if non-sensitive, seen_triples stays empty.
        Awaitility.await()
                .during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(4))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    int rows = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM seen_triples WHERE account_id = ?",
                            Integer.class, accountId);
                    assertThat(rows).isZero();
                });

        assertThat(output.toString()).doesNotContain("SECURITY_ALERT");
    }

    // -------------------------------------------------------------------------
    // Test 5: service toggled public → private → alert is MEDIUM
    // -------------------------------------------------------------------------

    @Test
    void serviceToggledToPrivate_alertIsMedium(CapturedOutput output) {
        markPublic("internal-svc", true);
        markPublic("internal-svc", false);

        postEvents(List.of(
                event("gateway", "internal-svc", Map.of("lastName", "LAST_NAME"))
        ));

        awaitAlert(output, "LAST_NAME");

        assertThat(output.toString())
                .contains("SECURITY_ALERT")
                .contains("LAST_NAME")
                .contains("MEDIUM")
                .doesNotContain("HIGH");
    }

    // -------------------------------------------------------------------------
    // Test 6: GET /graph returns vis.js-compatible nodes + edges
    // Graph is built from seen_triples, so we wait for the alert (which confirms
    // the triple was persisted) before querying.
    // -------------------------------------------------------------------------

    @Test
    void graphApi_returnsNodesAndEdgesForAccount(CapturedOutput output) {
        postEvents(List.of(
                event("svc-a", "svc-b", Map.of("cc", "CREDIT_CARD_NUMBER"))
        ));

        awaitAlert(output, "CREDIT_CARD_NUMBER");

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "/graph",
                HttpMethod.GET,
                new HttpEntity<>(headers()),
                (Class<Map<String, Object>>) (Class<?>) Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> body = response.getBody();
        assertThat(body).containsKey("nodes");
        assertThat(body).containsKey("edges");

        List<?> nodes = (List<?>) body.get("nodes");
        List<?> edges = (List<?>) body.get("edges");

        assertThat(nodes).anyMatch(n -> ((Map<?, ?>) n).get("label").toString().contains("svc-a"));
        assertThat(nodes).anyMatch(n -> ((Map<?, ?>) n).get("label").toString().contains("svc-b"));
        assertThat(edges).isNotEmpty();
    }
}
