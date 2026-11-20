package com.wenghong.notifyflow.identity.application;

public class InvalidRegistrationPasswordException extends RuntimeException {

    public InvalidRegistrationPasswordException() {
        super("Password does not meet the registration requirements.");
    }
}