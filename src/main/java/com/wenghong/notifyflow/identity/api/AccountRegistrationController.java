package com.wenghong.notifyflow.identity.api;

import org.springframework.web.bind.annotation.RestController;

import com.wenghong.notifyflow.identity.application.AccountRegistrationResult;
import com.wenghong.notifyflow.identity.application.AccountRegistrationService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.wenghong.notifyflow.identity.application.EmailAlreadyRegisteredException;
import com.wenghong.notifyflow.identity.application.InvalidRegistrationPasswordException;

@RestController
@RequestMapping("/api/auth")
public class AccountRegistrationController {

    private final AccountRegistrationService service;

    public AccountRegistrationController(AccountRegistrationService service) {
        this.service = service;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)

    public AccountRegistrationResult register(@Valid @RequestBody RegisterAccountRequest request) {
        return service.register(request.email(), request.password(), request.tenantName());
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ProblemDetail handleEmailAlreadyRegistered(
            EmailAlreadyRegisteredException exception) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                exception.getMessage());
    }

    @ExceptionHandler(InvalidRegistrationPasswordException.class)
    public ProblemDetail handleInvalidPassword(
            InvalidRegistrationPasswordException exception) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                exception.getMessage());
    }
}
