package com.wenghong.notifyflow.identity.api;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisterAccountRequestTests {

    @Test
    void beanValidationDiscoversPasswordPolicyGetter() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var invalid = new RegisterAccountRequest("alice@example.com", "short", "Company A");
            var errors = validator.validate(invalid);

            assertTrue(errors.stream().anyMatch(error -> error.getPropertyPath().toString().equals("passwordValid")));
            assertTrue(validator.validate(new RegisterAccountRequest(
                    "alice@example.com", "NotifyFlow-test-password-128", "Company A")).isEmpty());
        }
    }

    @Test
    void textRepresentationDoesNotExposeRawPassword() {
        String password = "NotifyFlow-test-password-128";
        var request = new RegisterAccountRequest("alice@example.com", password, "Company A");

        assertFalse(request.toString().contains(password));
        assertEquals("RegisterAccountRequest[REDACTED]", request.toString());
    }
}
