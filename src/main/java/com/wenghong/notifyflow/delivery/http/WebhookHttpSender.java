package com.wenghong.notifyflow.delivery.http;

import java.io.IOException;
import java.net.URI;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.springframework.stereotype.Component;

import com.wenghong.notifyflow.delivery.application.DeliveryWork;
import com.wenghong.notifyflow.endpoint.application.InvalidWebhookUrlException;
import com.wenghong.notifyflow.endpoint.application.WebhookUrlPolicy;

@Component
public class WebhookHttpSender {
    private final CloseableHttpClient httpClient;
    private final WebhookSigner signer;
    private final WebhookUrlPolicy urlPolicy;

    private final Clock clock;

    public WebhookHttpSender(
            CloseableHttpClient httpClient,
            WebhookSigner signer,
            Clock clock,
            WebhookUrlPolicy urlPolicy) {
        this.httpClient = httpClient;
        this.signer = signer;
        this.clock = clock;
        this.urlPolicy = urlPolicy;
    }

    public int send(DeliveryWork work)
            throws IOException, InterruptedException {

        URI uri;
        try {
            uri = urlPolicy.validate(work.url());
        } catch (InvalidWebhookUrlException exception) {
            throw new IOException("Webhook destination rejected", exception);
        }

        String body = work.payload().toString();
        Instant sentAt = clock.instant();
        long timestamp = sentAt.getEpochSecond();
        String signature;
        try {
            signature = signer.signWithRotation(
                    work.signingSecret(), work.previousSigningSecret(), work.previousSigningSecretExpiresAt(),
                    work.id(), sentAt, body);
        } catch (GeneralSecurityException exception) {
            throw new IOException("Could not sign webhook request", exception);
        }

        HttpPost request = new HttpPost(uri);
        request.setHeader("webhook-id", work.id().toString());
        request.setHeader("webhook-timestamp", Long.toString(timestamp));
        request.setHeader("webhook-signature", signature);
        request.setEntity(new StringEntity(
                body,
                ContentType.create("application/json", StandardCharsets.UTF_8)));

        return httpClient.execute(request, response -> response.getCode());
    }
}
