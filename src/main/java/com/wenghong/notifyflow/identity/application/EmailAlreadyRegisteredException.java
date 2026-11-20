package com.wenghong.notifyflow.identity.application;

public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException(Throwable cause) {
        super("An account with this email already exists.", cause);
    }
}