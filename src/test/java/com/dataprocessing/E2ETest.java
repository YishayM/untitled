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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests. All scenarios are written upfront (TDD).
 * These tests fail until each feature is implemented in subsequent PRs.
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

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    private static final String ACCOUNT = "acct-test";

    @BeforeEach
    void cleanDb() {
        jdbc.execute("DELETE FROM seen_triples");
        jdbc.execute("DELETE FROM services");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Account-ID", ACCOUNT);
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

    private void awaitAlert(CapturedOutput output) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(output.toString()).contains("SECURITY_ALERT"));
    }

    private long countAlerts(CapturedOutput output, String source, String destination, String classification) {
        return output.toString().lines()
                .filter(line -> line.contains("SECURITY_ALERT")
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
                        "price",     "NUMBER"        // non-sensitive — no alert for this key
                ))
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        awaitAlert(output);

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

        awaitAlert(output);

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
    void duplicateEvents_fireAlertExactlyOnce(CapturedOutput output) throws InterruptedException {
        var events = List.of(
                event("auth", "db", Map.of("ssn", "SOCIAL_SECURITY_NUMBER"))
        );

        postEvents(events);
        postEvents(events);
        postEvents(events);

        awaitAlert(output);

        // Extra wait to let any spurious duplicates appear
        Thread.sleep(1500);

        long count = countAlerts(output, "auth", "db", "SOCIAL_SECURITY_NUMBER");
        assertThat(count).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Test 4: non-sensitive types → no alert
    // -------------------------------------------------------------------------

    @Test
    void nonSensitiveClassifications_doNotFireAlert(CapturedOutput output) throws InterruptedException {
        postEvents(List.of(
                event("analytics", "warehouse", Map.of(
                        "count",     "NUMBER",
                        "eventDate", "DATE"
                ))
        ));

        Thread.sleep(2000);

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

        awaitAlert(output);

        assertThat(output.toString())
                .contains("SECURITY_ALERT")
                .contains("LAST_NAME")
                .contains("MEDIUM")
                .doesNotContain("HIGH");
    }

    // -------------------------------------------------------------------------
    // Test 6: GET /graph returns vis.js-compatible nodes + edges
    // -------------------------------------------------------------------------

    @Test
    void graphApi_returnsNodesAndEdgesForAccount(CapturedOutput output) {
        postEvents(List.of(
                event("svc-a", "svc-b", Map.of("cc", "CREDIT_CARD_NUMBER"))
        ));

        // Wait for async processing to persist to seen_triples
        awaitAlert(output);

        ResponseEntity<Map> response = rest.exchange(
                "/graph",
                HttpMethod.GET,
                new HttpEntity<>(headers()),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsKey("nodes");
        assertThat(body).containsKey("edges");

        List<?> nodes = (List<?>) body.get("nodes");
        List<?> edges = (List<?>) body.get("edges");

        assertThat(nodes).anyMatch(n -> ((Map<?, ?>) n).get("label").toString().contains("svc-a"));
        assertThat(nodes).anyMatch(n -> ((Map<?, ?>) n).get("label").toString().contains("svc-b"));
        assertThat(edges).isNotEmpty();
    }
}
