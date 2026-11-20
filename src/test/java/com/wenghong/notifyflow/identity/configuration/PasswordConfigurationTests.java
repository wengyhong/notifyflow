package com.wenghong.notifyflow.identity.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordConfigurationTests {

    private final PasswordEncoder encoder = new PasswordConfiguration().passwordEncoder();

    @Test
    void encodedPasswordMatchesOnlyTheCorrectPassword() {
        String password = "NotifyFlow-test-password-128";

        String encoded = encoder.encode(password);

        assertNotEquals(password, encoded);
        assertTrue(encoder.matches(password, encoded));
        assertFalse(encoder.matches("wrong-password", encoded));
    }

    @Test
    void encodingSamePasswordUsesIndependentSalts() {
        String password = "NotifyFlow-test-password-128";

        String first = encoder.encode(password);
        String second = encoder.encode(password);

        assertNotEquals(first, second);
        assertTrue(encoder.matches(password, first));
        assertTrue(encoder.matches(password, second));
    }
}
