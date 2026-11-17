package com.wenghong.notifyflow.endpoint.application;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

@Component
public class WebhookSecretGenerator {

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        var secretBytes = new byte[32];

        random.nextBytes(secretBytes);

        return Base64.getEncoder().encodeToString(secretBytes);
    }
}
