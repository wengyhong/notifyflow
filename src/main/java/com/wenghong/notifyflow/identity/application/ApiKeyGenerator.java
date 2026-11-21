package com.wenghong.notifyflow.identity.application;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

@Component
public class ApiKeyGenerator {

    private final SecureRandom random = new SecureRandom();

    public String generate()
    {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);

        return "nf_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
