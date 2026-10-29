package com.wenghong.notifyflow.endpoint.api;

import jakarta.validation.constraints.NotNull;

public record ChangeWebhookEndpointEnabledRequest(
    @NotNull
    Boolean enabled) {

}
