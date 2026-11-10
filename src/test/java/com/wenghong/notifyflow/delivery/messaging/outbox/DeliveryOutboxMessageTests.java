package com.wenghong.notifyflow.delivery.messaging.outbox;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeliveryOutboxMessageTests {

    private static final UUID DELIVERY_ID = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
    private static final Instant CREATED_AT = Instant.parse("2026-11-10T00:00:00Z");
    private static final Instant PUBLISHED_AT = CREATED_AT.plusMillis(250);

    @Test
    void newMessageIsUnpublishedAndPreservesDeliveryAndCreationTime() {
        DeliveryOutboxMessage message = newMessage();

        assertEquals(DELIVERY_ID, message.getDeliveryId());
        assertEquals(CREATED_AT, message.getCreatedAt());
        assertNull(message.getPublishedAt());
    }

    @Test
    void constructorRejectsMissingDeliveryId() {
        assertThrows(NullPointerException.class, () -> new DeliveryOutboxMessage(null, CREATED_AT));
    }

    @Test
    void constructorRejectsMissingCreationTime() {
        assertThrows(NullPointerException.class, () -> new DeliveryOutboxMessage(DELIVERY_ID, null));
    }

    @Test
    void markPublishedRecordsExactTimestampAndPreservesMessageDetails() {
        DeliveryOutboxMessage message = newMessage();

        message.markPublished(PUBLISHED_AT);

        assertEquals(PUBLISHED_AT, message.getPublishedAt());
        assertEquals(DELIVERY_ID, message.getDeliveryId());
        assertEquals(CREATED_AT, message.getCreatedAt());
    }

    @Test
    void nullPublicationTimeLeavesMessageUnpublishedAndAllowsLaterPublication() {
        DeliveryOutboxMessage message = newMessage();

        assertThrows(NullPointerException.class, () -> message.markPublished(null));

        assertNull(message.getPublishedAt());
        message.markPublished(PUBLISHED_AT);
        assertEquals(PUBLISHED_AT, message.getPublishedAt());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"2026-11-10T00:00:00.250Z", "2026-11-10T00:00:01Z"})
    void publishedMessageCannotBeMarkedAgainOrHaveItsTimestampOverwritten(String replacement) {
        DeliveryOutboxMessage message = newMessage();
        message.markPublished(PUBLISHED_AT);
        Instant replacementTime = replacement == null ? null : Instant.parse(replacement);

        assertThrows(IllegalStateException.class, () -> message.markPublished(replacementTime));

        assertEquals(PUBLISHED_AT, message.getPublishedAt());
        assertEquals(DELIVERY_ID, message.getDeliveryId());
        assertEquals(CREATED_AT, message.getCreatedAt());
    }

    private DeliveryOutboxMessage newMessage() {
        return new DeliveryOutboxMessage(DELIVERY_ID, CREATED_AT);
    }
}
