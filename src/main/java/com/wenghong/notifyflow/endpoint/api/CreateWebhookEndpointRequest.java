package com.wenghong.notifyflow.endpoint.api;

import org.hibernate.validator.constraints.URL;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWebhookEndpointRequest(
    @NotBlank @Size(max =100)
    String name,

    @NotBlank
    @URL(regexp = "(?i)https?://.+")

    String url) {

}
