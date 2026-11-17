package com.wenghong.notifyflow.endpoint.application;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebhookSecretGeneratorTests {

    @Test
    void generatesBase64Encoded32ByteSecret() {
        String secret = new WebhookSecretGenerator().generate();

        assertEquals(44, secret.length());
        byte[] decoded = Base64.getDecoder().decode(secret);
        assertEquals(32, decoded.length);
        assertEquals(secret, Base64.getEncoder().encodeToString(decoded));
    }
}
