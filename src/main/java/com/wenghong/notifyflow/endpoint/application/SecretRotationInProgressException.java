package com.wenghong.notifyflow.endpoint.application;

public class SecretRotationInProgressException extends RuntimeException {

    public SecretRotationInProgressException() {
        super("A signing secret rotation is already in progress");
    }
}