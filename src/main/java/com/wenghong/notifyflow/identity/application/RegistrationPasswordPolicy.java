package com.wenghong.notifyflow.identity.application;

import java.nio.charset.StandardCharsets;

public final class RegistrationPasswordPolicy {

    private RegistrationPasswordPolicy() {
    }

    public static boolean isValid(String password) {
        if (password == null || password.isBlank()) {
            return false;
        }

        return password.codePointCount(0, password.length()) >= 15
                && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}