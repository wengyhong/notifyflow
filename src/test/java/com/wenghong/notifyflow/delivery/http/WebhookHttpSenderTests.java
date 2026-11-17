package com.wenghong.notifyflow.delivery.http;

import java.net.InetSocketAddress;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import com.wenghong.notifyflow.delivery.application.DeliveryWork;
import com.wenghong.notifyflow.delivery.configuration.WebhookHttpConfiguration;
import com.wenghong.notifyflow.endpoint.application.InvalidWebhookUrlException;
import com.wenghong.notifyflow.endpoint.application.WebhookAddressPolicy;
import com.wenghong.notifyflow.endpoint.application.WebhookHostResolver;
import com.wenghong.notifyflow.endpoint.application.WebhookUrlPolicy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WebhookHttpSenderTests {

    private static final String SIGNING_SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-11-14T00:00:00Z"), ZoneOffset.UTC);
    private final WebhookHostResolver resolver = mock(WebhookHostResolver.class);
    private final WebhookUrlPolicy localPolicy = new WebhookUrlPolicy(
            resolver, new WebhookAddressPolicy(), true);

    @BeforeEach
    void resolveTestHostsWithoutLiveDns() throws Exception {
        when(resolver.resolve(anyString())).thenReturn(new InetAddress[] {
                InetAddress.getByName("127.0.0.1")
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 429, 500, 503})
    void returnsErrorStatusWithoutThrowing(int responseStatus) throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            try {
                hits.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(responseStatus, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();

        try (CloseableHttpClient client = client(localPolicy)) {
            WebhookHttpSender sender = new WebhookHttpSender(client, new WebhookSigner(), CLOCK, localPolicy);
            DeliveryWork work = new DeliveryWork(
                    UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd"),
                    UUID.fromString("e292322e-31ce-4892-bf9d-33e3eba2c5d7"),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook",
                    JsonMapper.builder().build().readTree("{\"orderNumber\":\"128\"}"),
                    SIGNING_SECRET, null, null);

            assertEquals(responseStatus, sender.send(work));
            assertEquals(1, hits.get(), "Apache must leave retry decisions to NotifyFlow");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsSignedJsonPostAndReturnsResponseStatus() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        JsonNode payload = json.readTree("""
                {"orderNumber":"128","note":"caf\u00e9","items":[{"sku":"A","quantity":2}]}
                """);
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(new ReceivedRequest(
                        exchange.getRequestMethod(),
                        exchange.getRequestHeaders().getFirst("Content-Type"),
                        body,
                        exchange.getRequestHeaders().getFirst("webhook-id"),
                        exchange.getRequestHeaders().getFirst("webhook-timestamp"),
                        exchange.getRequestHeaders().getFirst("webhook-signature"),
                        exchange.getRequestHeaders().getFirst("Host")));
                exchange.sendResponseHeaders(204, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();

        try (CloseableHttpClient client = client(localPolicy)) {
            WebhookHttpSender sender = new WebhookHttpSender(client, new WebhookSigner(), CLOCK, localPolicy);
            DeliveryWork work = new DeliveryWork(
                    UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd"),
                    UUID.fromString("e292322e-31ce-4892-bf9d-33e3eba2c5d7"),
                    "http://receiver.invalid:" + server.getAddress().getPort() + "/webhook",
                    payload,
                    SIGNING_SECRET, null, null);

            int status = sender.send(work);

            assertEquals(204, status);
            ReceivedRequest received = requests.poll(5, TimeUnit.SECONDS);
            assertNotNull(received, "The local server did not receive the webhook request");
            assertEquals("POST", received.method());
            ContentType contentType = ContentType.parse(received.contentType());
            assertEquals("application/json", contentType.getMimeType());
            assertEquals(StandardCharsets.UTF_8, contentType.getCharset());
            assertEquals("receiver.invalid:" + server.getAddress().getPort(), received.host());
            assertEquals(payload, json.readTree(received.body()));
            assertEquals(work.id().toString(), received.webhookId());
            long timestamp = CLOCK.instant().getEpochSecond();
            assertEquals(Long.toString(timestamp), received.timestamp());
            String expectedSignature = new WebhookSigner().sign(
                    SIGNING_SECRET, work.id(), timestamp, received.body());
            assertEquals(expectedSignature, received.signature());
            verify(resolver, times(2)).resolve("receiver.invalid");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void blockedLiteralDestinationNeverReachesServer() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = countingServer(hits);
        WebhookUrlPolicy strictPolicy = new WebhookUrlPolicy(resolver, new WebhookAddressPolicy(), false);

        try (CloseableHttpClient client = client(strictPolicy)) {
            WebhookHttpSender sender = new WebhookHttpSender(client, new WebhookSigner(), CLOCK, strictPolicy);
            IOException error = assertThrows(IOException.class, () -> sender.send(work(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook")));

            assertEquals("Webhook destination rejected", error.getMessage());
            assertInstanceOf(InvalidWebhookUrlException.class, error.getCause());
            assertEquals(0, hits.get());
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1", "10.0.0.1", "169.254.169.254", "fd00::1"})
    void blockedResolutionStopsBeforeSigningOrExecutingHttp(String literal) throws Exception {
        when(resolver.resolve("receiver.invalid")).thenReturn(new InetAddress[] {
                InetAddress.getByName(literal)
        });
        WebhookUrlPolicy strictPolicy = new WebhookUrlPolicy(resolver, new WebhookAddressPolicy(), false);
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        WebhookSigner signer = mock(WebhookSigner.class);
        WebhookHttpSender sender = new WebhookHttpSender(client, signer, CLOCK, strictPolicy);

        IOException error = assertThrows(IOException.class,
                () -> sender.send(work("http://receiver.invalid/webhook")));

        assertEquals("Webhook destination rejected", error.getMessage());
        assertInstanceOf(InvalidWebhookUrlException.class, error.getCause());
        verifyNoInteractions(client, signer);
    }

    @Test
    void changedDnsAnswerIsRejectedDuringConnectionSetup() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = countingServer(hits);
        when(resolver.resolve("receiver.invalid"))
                .thenReturn(new InetAddress[] {InetAddress.getByName("8.8.8.8")})
                .thenReturn(new InetAddress[] {InetAddress.getByName("127.0.0.1")});
        WebhookUrlPolicy strictPolicy = new WebhookUrlPolicy(resolver, new WebhookAddressPolicy(), false);

        try (CloseableHttpClient client = client(strictPolicy)) {
            WebhookHttpSender sender = new WebhookHttpSender(client, new WebhookSigner(), CLOCK, strictPolicy);

            assertThrows(IOException.class, () -> sender.send(work(
                    "http://receiver.invalid:" + server.getAddress().getPort() + "/webhook")));

            verify(resolver, times(2)).resolve("receiver.invalid");
            assertEquals(0, hits.get(), "A changed DNS answer must not reach the local receiver");
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void returnsRedirectWithoutFollowingLocation(int redirectStatus) throws Exception {
        AtomicInteger targetHits = new AtomicInteger();
        AtomicInteger sourceHits = new AtomicInteger();
        HttpServer target = countingServer(targetHits);
        HttpServer source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        source.createContext("/webhook", exchange -> {
            try {
                sourceHits.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Location",
                        "http://127.0.0.1:" + target.getAddress().getPort() + "/webhook");
                exchange.sendResponseHeaders(redirectStatus, -1);
            } finally {
                exchange.close();
            }
        });
        source.start();

        try (CloseableHttpClient client = client(localPolicy)) {
            WebhookHttpSender sender = new WebhookHttpSender(client, new WebhookSigner(), CLOCK, localPolicy);

            assertEquals(redirectStatus, sender.send(work(
                    "http://127.0.0.1:" + source.getAddress().getPort() + "/webhook")));
            assertEquals(1, sourceHits.get());
            assertEquals(0, targetHits.get());
        } finally {
            source.stop(0);
            target.stop(0);
        }
    }

    @Test
    void signingFailureDoesNotExecuteHttp() throws Exception {
        when(resolver.resolve("receiver.invalid")).thenReturn(new InetAddress[] {
                InetAddress.getByName("8.8.8.8")
        });
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        WebhookSigner signer = mock(WebhookSigner.class);
        DeliveryWork work = work("https://receiver.invalid/webhook");
        var failure = new java.security.GeneralSecurityException("Test signing failure");
        when(signer.signWithRotation(SIGNING_SECRET, null, null,
                work.id(), CLOCK.instant(), work.payload().toString()))
                .thenThrow(failure);
        WebhookHttpSender sender = new WebhookHttpSender(client, signer, CLOCK, localPolicy);

        IOException error = assertThrows(IOException.class, () -> sender.send(work));

        assertEquals("Could not sign webhook request", error.getMessage());
        assertSame(failure, error.getCause());
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 1})
    void sendsRotationSignaturesUsingActualSendTimeAndExactExpiry(long expiryNanosAfterSendTime)
            throws Exception {
        String previousSecret = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        Instant claimedAt = CLOCK.instant();
        Instant sentAt = claimedAt.plusSeconds(60);
        // This previous secret was active at claim time in every case.
        Instant expiry = sentAt.plusNanos(expiryNanosAfterSendTime);
        Clock sendClock = mock(Clock.class);
        when(sendClock.instant()).thenReturn(sentAt, sentAt.plusSeconds(3600));
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        HttpServer server = capturingServer(requests);

        try (CloseableHttpClient client = client(localPolicy)) {
            WebhookHttpSender sender = new WebhookHttpSender(client, new WebhookSigner(), sendClock, localPolicy);
            DeliveryWork work = new DeliveryWork(UUID.randomUUID(), UUID.randomUUID(),
                    "http://receiver.invalid:" + server.getAddress().getPort() + "/webhook",
                    JsonMapper.builder().build().readTree("{\"orderId\":128,\"note\":\"caf\u00e9\"}"),
                    SIGNING_SECRET, previousSecret, expiry);

            assertEquals(204, sender.send(work));

            ReceivedRequest received = requests.poll(5, TimeUnit.SECONDS);
            assertNotNull(received);
            long timestamp = sentAt.getEpochSecond();
            assertEquals(work.id().toString(), received.webhookId());
            assertEquals(Long.toString(timestamp), received.timestamp());
            assertEquals(work.payload().toString(), received.body());
            String current = new WebhookSigner().sign(SIGNING_SECRET, work.id(), timestamp, received.body());
            String previous = new WebhookSigner().sign(previousSecret, work.id(), timestamp, received.body());
            boolean overlapActiveAtSend = expiryNanosAfterSendTime > 0;
            assertEquals(overlapActiveAtSend ? current + " " + previous : current, received.signature());
            assertEquals(overlapActiveAtSend ? 2 : 1, received.signature().split(" ").length);
            assertFalse(received.signature().contains(SIGNING_SECRET));
            assertFalse(received.signature().contains(previousSecret));
            verify(sendClock).instant();
        } finally {
            server.stop(0);
        }
    }

    private HttpServer capturingServer(BlockingQueue<ReceivedRequest> requests) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            try {
                var headers = exchange.getRequestHeaders();
                requests.add(new ReceivedRequest(exchange.getRequestMethod(), headers.getFirst("Content-Type"),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                        headers.getFirst("webhook-id"), headers.getFirst("webhook-timestamp"),
                        headers.getFirst("webhook-signature"), headers.getFirst("Host")));
                exchange.sendResponseHeaders(204, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private CloseableHttpClient client(WebhookUrlPolicy policy) {
        return new WebhookHttpConfiguration().webhookApacheHttpClient(new WebhookDnsResolver(policy));
    }

    private DeliveryWork work(String url) {
        return new DeliveryWork(UUID.randomUUID(), UUID.randomUUID(), url,
                JsonMapper.builder().build().readTree("{\"orderId\":128}"), SIGNING_SECRET, null, null);
    }

    private HttpServer countingServer(AtomicInteger hits) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            try {
                hits.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(204, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private record ReceivedRequest(
            String method,
            String contentType,
            String body,
            String webhookId,
            String timestamp,
            String signature,
            String host) {
    }
}
