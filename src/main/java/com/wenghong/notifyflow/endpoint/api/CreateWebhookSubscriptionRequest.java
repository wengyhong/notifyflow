package com.wenghong.notifyflow.endpoint.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWebhookSubscriptionRequest(

        @NotBlank @Size(max = 100) String eventType

) {

}
