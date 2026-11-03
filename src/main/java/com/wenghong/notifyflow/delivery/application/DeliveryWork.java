package com.wenghong.notifyflow.delivery.application;

import java.util.UUID;

import tools.jackson.databind.JsonNode;

public record DeliveryWork(

    UUID id,
    UUID attemptId,
    String url,
    JsonNode payload
) {

}
