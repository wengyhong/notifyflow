package com.wenghong.notifyflow.delivery.http;

import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;

import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;

import javax.crypto.spec.SecretKeySpec;

@Component
public class WebhookSigner {

    public String sign(
            String signingSecret,
            UUID deliveryId,
            long timestamp,
            String body) throws GeneralSecurityException {

        var secretBytes = Base64.getDecoder().decode(signingSecret);
        String algorithm = "HmacSHA256";

        var signedText = deliveryId + "." + timestamp + "." + body;

        SecretKeySpec secret = new SecretKeySpec(secretBytes, algorithm);
        var mac = Mac.getInstance(algorithm);

        mac.init(secret);

        byte[] rawHmac = mac.doFinal(signedText.getBytes(StandardCharsets.UTF_8));

        return "v1," + Base64.getEncoder().encodeToString(rawHmac);
    }

    public String signWithRotation(String signingSecret,
            String previousSigningSecret,
            Instant previousSecretExpiresAt,
            UUID deliveryId,
            Instant sentAt,
            String body) throws GeneralSecurityException {

        long timestamp = sentAt.getEpochSecond();
        String current = sign(signingSecret, deliveryId, timestamp, body);

        if (previousSigningSecret != null && previousSecretExpiresAt != null
                && sentAt.isBefore(previousSecretExpiresAt)) {

            String previous = sign(previousSigningSecret, deliveryId, timestamp, body);
            return current + " " + previous;
        }

        return current;

    }
}
