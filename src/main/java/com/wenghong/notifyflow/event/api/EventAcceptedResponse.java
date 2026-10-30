package com.wenghong.notifyflow.event.api;

import java.time.Instant;
import java.util.UUID;

public record EventAcceptedResponse(

    UUID id,
    String type,
    Instant createdAt
) {

}
