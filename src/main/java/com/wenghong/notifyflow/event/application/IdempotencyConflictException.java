package com.wenghong.notifyflow.event.application;

public class IdempotencyConflictException extends RuntimeException
{
    public IdempotencyConflictException(){
    super("The idempotency key has already been used for a different event.");
    }
}
