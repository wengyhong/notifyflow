package com.wenghong.notifyflow.identity.application;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RegistrationPasswordPolicyTests {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "               "})
    void rejectsAbsentOrBlankPasswords(String password) {
        assertFalse(RegistrationPasswordPolicy.isValid(password));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("boundaries")
    void checksCodePointsAndUtf8Bytes(String description, String password, boolean expected) {
        assertEquals(expected, RegistrationPasswordPolicy.isValid(password));
    }

    static Stream<Arguments> boundaries() {
        return Stream.of(
                Arguments.of("14 ASCII characters", "a".repeat(14), false),
                Arguments.of("15 ASCII characters", "a".repeat(15), true),
                Arguments.of("72 ASCII bytes", "a".repeat(72), true),
                Arguments.of("73 ASCII bytes", "a".repeat(73), false),
                Arguments.of("14 supplementary code points, not 28 characters", "😀".repeat(14), false),
                Arguments.of("15 supplementary code points", "😀".repeat(15), true),
                Arguments.of("18 emoji use exactly 72 bytes", "😀".repeat(18), true),
                Arguments.of("19 emoji exceed 72 bytes", "😀".repeat(19), false),
                Arguments.of("24 CJK characters use exactly 72 bytes", "界".repeat(24), true),
                Arguments.of("25 CJK characters exceed 72 bytes", "界".repeat(25), false),
                Arguments.of("36 accented characters use 72 bytes", "é".repeat(36), true),
                Arguments.of("37 accented characters exceed 72 bytes", "é".repeat(37), false),
                Arguments.of("spaces within a passphrase are allowed", " a valid passphrase ", true));
    }
}
