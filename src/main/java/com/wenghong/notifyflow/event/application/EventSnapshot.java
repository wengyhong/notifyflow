package com.wenghong.notifyflow.event.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.wenghong.notifyflow.event.domain.Event;

import tools.jackson.databind.JsonNode;

public record EventSnapshot(
                UUID id,
                String eventType,
                JsonNode payload,
                Instant createdAt) {

        public EventSnapshot {
                payload = Objects.requireNonNull(payload).deepCopy();
        }

        @Override
        public JsonNode payload() {
                return payload.deepCopy();
        }

        public static EventSnapshot from(Event event) {
    return new EventSnapshot(
            event.getId(),
            event.getEventType(),
            event.getPayload(),
            event.getCreatedAt());
}
}