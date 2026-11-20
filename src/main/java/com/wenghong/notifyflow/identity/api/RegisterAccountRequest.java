package com.wenghong.notifyflow.identity.api;

import com.wenghong.notifyflow.identity.application.RegistrationPasswordPolicy;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterAccountRequest(
        @NotBlank @Email @Size(max = 254) String email,

        @NotBlank String password,

        @NotBlank @Size(max = 100) String tenantName) {
    @Override
    public String toString() {
        return "RegisterAccountRequest[REDACTED]";
    }

    @AssertTrue(message = "Password must contain at least 15 characters and use at most 72 UTF-8 bytes")
    public boolean isPasswordValid() {
        return RegistrationPasswordPolicy.isValid(password);
    }
}