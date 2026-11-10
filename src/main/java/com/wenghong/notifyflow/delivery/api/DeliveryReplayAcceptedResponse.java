package com.wenghong.notifyflow.delivery.api;

import java.util.UUID;

import com.wenghong.notifyflow.delivery.domain.DeliveryStatus;

public record DeliveryReplayAcceptedResponse(UUID id, DeliveryStatus status) {

}
