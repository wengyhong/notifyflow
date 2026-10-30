package com.wenghong.notifyflow.endpoint.application;

public class DuplicateWebhookSubscriptionException extends RuntimeException {

    public DuplicateWebhookSubscriptionException(Throwable cause)
    {
       super("The endpoint is already subscribed to this event type.", cause);
    }

}
