package com.wenghong.notifyflow.identity.security;

import java.net.URI;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.wenghong.notifyflow.delivery.application.DeliveryRetryScheduler;
import com.wenghong.notifyflow.delivery.messaging.outbox.DeliveryOutboxScheduler;
import com.wenghong.notifyflow.delivery.observability.DeliveryAttemptOutcome;
import com.wenghong.notifyflow.delivery.observability.DeliveryMetrics;
import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.identity.domain.AppUser;
import com.wenghong.notifyflow.identity.persistence.AppUserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "management.server.address=127.0.0.1",
        // Expose info in this test only, to exercise the deny-all fallback.
        "management.endpoints.web.exposure.include=health,prometheus,info",
        "management.health.redis.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@ActiveProfiles("local")
@AutoConfigureMetrics
@Import({PostgresTestConfiguration.class, LocalManagementHttpIntegrationTests.RabbitConfiguration.class})
class LocalManagementHttpIntegrationTests {
    @Value("${local.server.port}") private int applicationPort;
    @Value("${local.management.port}") private int managementPort;
    @Autowired private DeliveryMetrics metrics;
    @Autowired private MeterRegistry registry;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private DeliveryRetryScheduler retryScheduler;
    @MockitoBean private DeliveryOutboxScheduler outboxScheduler;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void anonymousScrapeExportsAllFourOutcomesAndRecordedIncrementsWithoutCreatingASession() throws Exception {
        assertNotEquals(applicationPort, managementPort);
        double before = registry.get("notifyflow.webhook.attempts").tag("outcome", "success").counter().count();
        var timer = registry.get("notifyflow.webhook.duration").timer();
        long durationCountBefore = timer.count();
        double durationSumBefore = timer.totalTime(java.util.concurrent.TimeUnit.SECONDS);
        metrics.recordAttempt(DeliveryAttemptOutcome.SUCCESS, Duration.ofMillis(250));

        var response = send(managementPort, "GET", "/actuator/prometheus");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("set-cookie").isEmpty());
        for (DeliveryAttemptOutcome outcome : DeliveryAttemptOutcome.values()) {
            String label = outcome.name().toLowerCase(Locale.ROOT);
            var sample = Pattern.compile("(?m)^notifyflow_webhook_attempts_total\\{[^}\\r\\n]*outcome=\""
                    + label + "\"[^}\\r\\n]*}\\s+([0-9.eE+\\-]+)(?:\\s.*)?$")
                    .matcher(response.body());
            assertTrue(sample.find(), () -> "Missing outcome series: " + label);
            double expected = registry.get("notifyflow.webhook.attempts").tag("outcome", label).counter().count();
            assertEquals(expected, Double.parseDouble(sample.group(1)));
        }
        assertEquals(before + 1, registry.get("notifyflow.webhook.attempts").tag("outcome", "success").counter().count());
        assertEquals(durationCountBefore + 1, timer.count());
        assertEquals(durationSumBefore + 0.25, timer.totalTime(java.util.concurrent.TimeUnit.SECONDS), 0.000001);
        assertSample(response.body(), "notifyflow_webhook_duration_seconds_count", timer.count());
        assertSample(response.body(), "notifyflow_webhook_duration_seconds_sum",
                timer.totalTime(java.util.concurrent.TimeUnit.SECONDS));
    }

    private void assertSample(String scrape, String name, double expected) {
        var sample = Pattern.compile("(?m)^" + Pattern.quote(name)
                + "\\s+([0-9.eE+\\-]+)(?:\\s.*)?$").matcher(scrape);
        assertTrue(sample.find(), () -> "Missing metric: " + name);
        assertEquals(expected, Double.parseDouble(sample.group(1)), 0.000001);
    }

    @Test
    void anonymousHealthReadIsAllowed() throws Exception {
        var response = send(managementPort, "GET", "/actuator/health");
        assertEquals(200, response.statusCode());
        assertTrue(Pattern.compile("\"status\"\\s*:\\s*\"UP\"").matcher(response.body()).find());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/info", "/actuator"})
    void otherExposedManagementReadsAreDenied(String path) throws Exception {
        assertDenied(send(managementPort, "GET", path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "HEAD"})
    void prometheusAccessIsGetOnly(String method) throws Exception {
        assertDenied(send(managementPort, method, "/actuator/prometheus"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/me", "/api/endpoints", "/api/deliveries", "/api/key"})
    void applicationReadsStillRequireAuthentication(String path) throws Exception {
        assertEquals(401, send(applicationPort, "GET", path).statusCode());
    }

    @Test
    void producerIngestionStillRequiresItsBearerKey() throws Exception {
        assertEquals(401, send(applicationPort, "POST", "/api/producer/events").statusCode());
    }

    @Test
    void applicationLoginStillCreatesAUsableSessionAndAuthenticatedWritesStillRequireCsrf() throws Exception {
        String email = "management-test-" + UUID.randomUUID() + "@example.com";
        String password = "test-only-management-password";
        AppUser account = users.saveAndFlush(new AppUser(email, passwordEncoder.encode(password), Instant.now()));
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (var sessionClient = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            String base = "http://127.0.0.1:" + applicationPort;
            var csrfResponse = sessionClient.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf"))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, csrfResponse.statusCode());
            var csrf = JsonMapper.builder().build().readTree(csrfResponse.body());
            String form = "email=" + java.net.URLEncoder.encode(email, java.nio.charset.StandardCharsets.UTF_8)
                    + "&password=" + password;
            var login = sessionClient.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header(csrf.get("headerName").asString(), csrf.get("token").asString())
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(204, login.statusCode());

            var me = sessionClient.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/me"))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, me.statusCode());
            assertTrue(me.body().contains(account.getId().toString()));

            var missingCsrf = sessionClient.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/register"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, missingCsrf.statusCode());
        } finally {
            users.deleteById(account.getId());
        }
    }

    @Test
    void metricsAreNotServedOnTheApplicationPort() throws Exception {
        var response = send(applicationPort, "GET", "/actuator/prometheus");
        assertTrue(Set.of(401, 404).contains(response.statusCode()));
        assertFalse(response.body().contains("notifyflow_webhook_attempts_total"));
    }

    private HttpResponse<String> send(int port, String method, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", path.equals("/actuator/prometheus") ? "text/plain;version=0.0.4" : "*/*")
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void assertDenied(HttpResponse<String> response) {
        // Anonymous denial may be rendered as 401 by the application's error-dispatch chain.
        assertTrue(Set.of(401, 403).contains(response.statusCode()),
                () -> "Expected denied access, got HTTP " + response.statusCode());
        assertFalse(response.body().contains("notifyflow_webhook_attempts_total"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RabbitConfiguration {
        @Bean
        @ServiceConnection
        RabbitMQContainer rabbitContainer() {
            return new RabbitMQContainer("rabbitmq:4.4.0-management");
        }
    }
}
