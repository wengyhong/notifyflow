package com.wenghong.notifyflow.identity.application;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyHasherTests {
    private final ApiKeyHasher hasher = new ApiKeyHasher();

    @ParameterizedTest
    @MethodSource("sha256Vectors")
    void hashesExactUtf8InputToKnownSha256Hex(String input, String expected) {
        String hash = hasher.hash(input);

        assertEquals(expected, hash);
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    @Test
    void includesPrefixInHashedInput() {
        assertNotEquals(hasher.hash("abc"), hasher.hash("nf_abc"));
        assertEquals("194151433bfbeaf0c1975063584e3d9ed425432b4d17a75076cb10c020200288",
                hasher.hash("nf_abc"));
    }

    @Test
    void repeatedHashingDoesNotCarryStateFromPreviousInputs() {
        String expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        assertEquals(expected, hasher.hash("abc"));
        hasher.hash("nf_different-key");
        assertEquals(expected, hasher.hash("abc"));
        assertEquals(expected, hasher.hash("abc"));
    }

    @Test
    void rejectsNullInput() {
        assertThrows(NullPointerException.class, () -> hasher.hash(null));
    }

    static Stream<Arguments> sha256Vectors() {
        // Fixed expected values, calculated independently of the Java component.
        return Stream.of(
                Arguments.of("", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                Arguments.of("abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
                Arguments.of("nf_abc", "194151433bfbeaf0c1975063584e3d9ed425432b4d17a75076cb10c020200288"),
                Arguments.of("nf_東京", "e14a9feb426d5889e711666a5c440ebe7c44e98adf022563799be54cf84363dc"));
    }
}
