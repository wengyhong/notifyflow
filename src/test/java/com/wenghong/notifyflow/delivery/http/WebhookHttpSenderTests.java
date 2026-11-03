package com.wenghong.notifyflow.delivery.http;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import com.wenghong.notifyflow.delivery.application.DeliveryWork;
import com.wenghong.notifyflow.delivery.configuration.WebhookHttpConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WebhookHttpSenderTests {

    @ParameterizedTest
    @ValueSource(ints = {400, 429, 500, 503})
    void returnsErrorStatusWithoutThrowing(int responseStatus) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(responseStatus, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();

        try (HttpClient client = new WebhookHttpConfiguration().webhookHttpClient()) {
            WebhookHttpSender sender = new WebhookHttpSender(client);
            DeliveryWork work = new DeliveryWork(
                    UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd"),
                    UUID.fromString("e292322e-31ce-4892-bf9d-33e3eba2c5d7"),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook",
                    JsonMapper.builder().build().readTree("{\"orderNumber\":\"128\"}"));

            assertEquals(responseStatus, sender.send(work));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsJsonPostAndReturnsResponseStatus() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        JsonNode payload = json.readTree("""
                {"orderNumber":"128","items":[{"sku":"A","quantity":2}]}
                """);
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(new ReceivedRequest(
                        exchange.getRequestMethod(),
                        exchange.getRequestHeaders().getFirst("Content-Type"),
                        body));
                exchange.sendResponseHeaders(204, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();

        try (HttpClient client = new WebhookHttpConfiguration().webhookHttpClient()) {
            WebhookHttpSender sender = new WebhookHttpSender(client);
            DeliveryWork work = new DeliveryWork(
                    UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd"),
                    UUID.fromString("e292322e-31ce-4892-bf9d-33e3eba2c5d7"),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook",
                    payload);

            int status = sender.send(work);

            assertEquals(204, status);
            ReceivedRequest received = requests.poll(5, TimeUnit.SECONDS);
            assertNotNull(received, "The local server did not receive the webhook request");
            assertEquals("POST", received.method());
            assertEquals("application/json", received.contentType());
            assertEquals(payload, json.readTree(received.body()));
        } finally {
            server.stop(0);
        }
    }

    private record ReceivedRequest(String method, String contentType, String body) {
    }
}
