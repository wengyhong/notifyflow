package com.wenghong.notifyflow.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import org.springframework.stereotype.Component;

@Component
public class ApiKeyHasher {

    public String hash(String rawKey) {
        Objects.requireNonNull(rawKey);

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawKey.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);

        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", exception);
        }
    }
}
