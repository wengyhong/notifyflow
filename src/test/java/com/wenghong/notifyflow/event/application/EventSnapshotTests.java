package com.wenghong.notifyflow.event.application;

import com.wenghong.notifyflow.identity.domain.Tenant;

import java.time.Instant;
import java.util.UUID;

import com.wenghong.notifyflow.event.domain.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventSnapshotTests {

    private final Tenant fixtureTenant = new Tenant("Fixture workspace", Instant.parse("2026-11-20T00:00:00Z"));

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-18T00:00:00Z");

    @Test
    void constructorCopiesNestedObjectsAndArrays() {
        JsonNode original = JSON.readTree("{\"items\":[{\"quantity\":1}]}");
        EventSnapshot snapshot = new EventSnapshot(ID, "order.confirmed", original, NOW);

        ((ObjectNode) original.get("items").get(0)).put("quantity", 99);

        assertEquals(JSON.readTree("{\"items\":[{\"quantity\":1}]}"), snapshot.payload());
    }

    @Test
    void accessorReturnsDefensiveCopyOfNestedPayload() {
        EventSnapshot snapshot = new EventSnapshot(ID, "order.confirmed",
                JSON.readTree("{\"items\":[{\"quantity\":1}]}"), NOW);
        EventSnapshot equalBeforeMutation = new EventSnapshot(ID, "order.confirmed", snapshot.payload(), NOW);
        int hashBeforeMutation = snapshot.hashCode();

        ((ObjectNode) snapshot.payload().get("items").get(0)).put("quantity", 99);

        assertEquals(equalBeforeMutation, snapshot);
        assertEquals(hashBeforeMutation, snapshot.hashCode());
        assertEquals(1, snapshot.payload().get("items").get(0).get("quantity").intValue());
    }

    @Test
    void factoryCopiesEventMetadataAndProtectsAgainstLaterEntityPayloadMutation() {
        JsonNode payload = JSON.readTree("{\"number\":128}");
        Event event = new Event(fixtureTenant, "order.confirmed", payload, "request-1", NOW);
        ReflectionTestUtils.setField(event, "id", ID);

        EventSnapshot snapshot = EventSnapshot.from(event);
        ((ObjectNode) event.getPayload()).put("number", 999);

        assertEquals(ID, snapshot.id());
        assertEquals("order.confirmed", snapshot.eventType());
        assertEquals(NOW, snapshot.createdAt());
        assertEquals(JSON.readTree("{\"number\":128}"), snapshot.payload());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "[1,{\"x\":true}]", "\"text\"", "42", "true", "null"})
    void supportsAllJsonNodeShapesAndJsonRoundTrips(String value) {
        EventSnapshot snapshot = new EventSnapshot(ID, "order.confirmed", JSON.readTree(value), NOW);

        assertEquals(snapshot, JSON.readValue(JSON.writeValueAsString(snapshot), EventSnapshot.class));
    }

    @Test
    void rejectsJavaNullPayload() {
        assertThrows(NullPointerException.class, () -> new EventSnapshot(ID, "order.confirmed", null, NOW));
    }
}
