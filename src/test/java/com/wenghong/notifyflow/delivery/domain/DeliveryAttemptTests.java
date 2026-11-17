package com.wenghong.notifyflow.delivery.domain;

import java.time.Instant;

import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.event.domain.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeliveryAttemptTests {

    private static final String TEST_SIGNING_SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    private static final Instant STARTED_AT = Instant.parse("2026-11-02T00:00:00Z");
    private static final Instant COMPLETED_AT = STARTED_AT.plusSeconds(2);

    @Test
    void newAttemptHasNoOutcome() {
        DeliveryAttempt attempt = newAttempt();

        assertEquals(1, attempt.getAttemptNumber());
        assertUnfinished(attempt);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsNonPositiveAttemptNumbers(int attemptNumber) {
        Delivery delivery = newDelivery();

        assertThrows(IllegalArgumentException.class,
                () -> new DeliveryAttempt(delivery, attemptNumber, STARTED_AT));
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 200, 204, 400, 503, 599})
    void completesWithHttpResponseIncludingErrorStatuses(int status) {
        DeliveryAttempt attempt = newAttempt();

        attempt.completeWithResponse(status, COMPLETED_AT);

        assertEquals(Integer.valueOf(status), attempt.getResponseStatus());
        assertEquals(COMPLETED_AT, attempt.getCompletedAt());
        assertEquals(STARTED_AT, attempt.getStartedAt());
        assertNull(attempt.getErrorMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 99, 600})
    void rejectsInvalidHttpStatusWithoutChangingAttempt(int status) {
        DeliveryAttempt attempt = newAttempt();

        assertThrows(IllegalArgumentException.class,
                () -> attempt.completeWithResponse(status, COMPLETED_AT));

        assertUnfinished(attempt);
    }

    @Test
    void rejectsNullResponseCompletionTimeWithoutChangingAttempt() {
        DeliveryAttempt attempt = newAttempt();

        assertThrows(NullPointerException.class,
                () -> attempt.completeWithResponse(204, null));

        assertUnfinished(attempt);
    }

    @Test
    void completesWithNetworkFailureWithoutHttpStatus() {
        DeliveryAttempt attempt = newAttempt();

        attempt.completeWithFailure("Connection timed out", COMPLETED_AT);

        assertEquals("Connection timed out", attempt.getErrorMessage());
        assertEquals(COMPLETED_AT, attempt.getCompletedAt());
        assertEquals(STARTED_AT, attempt.getStartedAt());
        assertNull(attempt.getResponseStatus());
    }

    @Test
    void rejectsNullErrorMessageWithoutChangingAttempt() {
        DeliveryAttempt attempt = newAttempt();

        assertThrows(NullPointerException.class,
                () -> attempt.completeWithFailure(null, COMPLETED_AT));

        assertUnfinished(attempt);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n"})
    void rejectsBlankErrorMessageWithoutChangingAttempt(String message) {
        DeliveryAttempt attempt = newAttempt();

        assertThrows(IllegalArgumentException.class,
                () -> attempt.completeWithFailure(message, COMPLETED_AT));

        assertUnfinished(attempt);
    }

    @Test
    void rejectsNullFailureCompletionTimeWithoutChangingAttempt() {
        DeliveryAttempt attempt = newAttempt();

        assertThrows(NullPointerException.class,
                () -> attempt.completeWithFailure("Connection timed out", null));

        assertUnfinished(attempt);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void completedAttemptCannotBeOverwrittenByEitherCompletionMethod(boolean receivedResponse) {
        DeliveryAttempt attempt = newAttempt();
        if (receivedResponse) {
            attempt.completeWithResponse(503, COMPLETED_AT);
        } else {
            attempt.completeWithFailure("Connection timed out", COMPLETED_AT);
        }
        Integer originalStatus = attempt.getResponseStatus();
        String originalError = attempt.getErrorMessage();

        assertThrows(IllegalStateException.class,
                () -> attempt.completeWithResponse(204, COMPLETED_AT.plusSeconds(1)));
        assertEquals(COMPLETED_AT, attempt.getCompletedAt());
        assertEquals(originalStatus, attempt.getResponseStatus());
        assertEquals(originalError, attempt.getErrorMessage());

        assertThrows(IllegalStateException.class,
                () -> attempt.completeWithFailure("Different failure", COMPLETED_AT.plusSeconds(1)));
        assertEquals(COMPLETED_AT, attempt.getCompletedAt());
        assertEquals(originalStatus, attempt.getResponseStatus());
        assertEquals(originalError, attempt.getErrorMessage());
        assertEquals(STARTED_AT, attempt.getStartedAt());
    }

    private void assertUnfinished(DeliveryAttempt attempt) {
        assertEquals(STARTED_AT, attempt.getStartedAt());
        assertNull(attempt.getCompletedAt());
        assertNull(attempt.getResponseStatus());
        assertNull(attempt.getErrorMessage());
    }

    private DeliveryAttempt newAttempt() {
        return new DeliveryAttempt(newDelivery(), 1, STARTED_AT);
    }

    private Delivery newDelivery() {
        Event event = new Event(
                "order.confirmed", JsonMapper.builder().build().readTree("{\"orderNumber\":\"128\"}"),
                "order-128-confirmed", STARTED_AT);
        WebhookEndpoint endpoint = new WebhookEndpoint(
                "Warehouse", "https://example.com/webhooks", STARTED_AT, TEST_SIGNING_SECRET);
        return new Delivery(event, endpoint, STARTED_AT);
    }
}
