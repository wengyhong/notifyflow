package com.wenghong.notifyflow.delivery.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.stereotype.Component;

import com.wenghong.notifyflow.delivery.application.DeliveryWork;

@Component
public class WebhookHttpSender {
    private final HttpClient httpClient;

    public WebhookHttpSender(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public int send(DeliveryWork work) throws IOException, InterruptedException
    {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(work.url())).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(work.payload().toString())).build();

            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());

            return response.statusCode();
    }
}
