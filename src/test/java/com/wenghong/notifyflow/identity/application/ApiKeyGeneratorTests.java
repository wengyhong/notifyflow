package com.wenghong.notifyflow.identity.application;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyGeneratorTests {
    @Test
    void generatesPrefixedUrlSafeKeyContainingThirtyTwoBytes() {
        String rawKey = new ApiKeyGenerator().generate();

        assertTrue(rawKey.matches("nf_[A-Za-z0-9_-]{43}"));
        assertEquals(46, rawKey.length());
        assertEquals(32, Base64.getUrlDecoder().decode(rawKey.substring(3)).length);
    }

    @Test
    void successiveCallsProduceDifferentKeys() {
        ApiKeyGenerator generator = new ApiKeyGenerator();

        assertNotEquals(generator.generate(), generator.generate());
    }
}
