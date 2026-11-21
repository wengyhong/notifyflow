package com.wenghong.notifyflow.endpoint.domain;

import java.time.Instant;
import java.util.stream.Stream;

import com.wenghong.notifyflow.identity.domain.Tenant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class WebhookEndpointTests {

    private final Tenant fixtureTenant = new Tenant("Fixture workspace", Instant.parse("2026-11-20T00:00:00Z"));

    @Test
    void tenantAwareConstructorRetainsItsOwner() {
        var tenant = new Tenant("Company A", CREATED);
        var endpoint = new WebhookEndpoint(tenant, "Orders", "https://example.com/webhook",
                CREATED, TEST_SIGNING_SECRET);
        assertSame(tenant, endpoint.getTenant());
    }

    @Test
    void tenantAwareConstructorRejectsMissingOwner() {
        assertThrows(NullPointerException.class, () -> new WebhookEndpoint(null,
                "Orders", "https://example.com/webhook", CREATED, TEST_SIGNING_SECRET));
    }

    private static final String TEST_SIGNING_SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String NEXT_SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String THIRD_SECRET = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";
    private static final Instant CREATED = Instant.parse("2026-11-17T00:00:00Z");
    private static final Instant ROTATED = CREATED.plusSeconds(60);
    private static final Instant EXPIRES = ROTATED.plusSeconds(3600);

    @Test
    void newEndpointHasNoPreviousSecret() {
        WebhookEndpoint endpoint = endpoint();

        assertNull(endpoint.getPreviousSigningSecret());
        assertNull(endpoint.getPreviousSigningSecretExpiresAt());
        assertFalse(endpoint.hasActivePreviousSigningSecret(ROTATED));
    }

    @Test
    void rotationReplacesCurrentSecretAndPreservesUnrelatedEndpointFields() {
        WebhookEndpoint endpoint = endpoint();
        endpoint.changeEnabled(false, CREATED.plusSeconds(1));

        endpoint.rotateSigningSecret(NEXT_SECRET, ROTATED, EXPIRES);

        assertEquals(NEXT_SECRET, endpoint.getSigningSecret());
        assertEquals(TEST_SIGNING_SECRET, endpoint.getPreviousSigningSecret());
        assertEquals(EXPIRES, endpoint.getPreviousSigningSecretExpiresAt());
        assertEquals(ROTATED, endpoint.getUpdatedAt());
        assertEquals(CREATED, endpoint.getCreatedAt());
        assertEquals("Orders", endpoint.getName());
        assertEquals("https://example.com/webhook", endpoint.getUrl());
        assertFalse(endpoint.isEnabled());
    }

    @ParameterizedTest
    @CsvSource({"-1, true", "0, false", "1, false"})
    void previousSecretIsInactiveAtExactExpiry(long nanosFromExpiry, boolean active) {
        WebhookEndpoint endpoint = rotatedEndpoint();

        assertEquals(active, endpoint.hasActivePreviousSigningSecret(EXPIRES.plusNanos(nanosFromExpiry)));
    }

    @Test
    void rotationDuringActiveOverlapIsRejectedWithoutChangingState() {
        WebhookEndpoint endpoint = rotatedEndpoint();
        SecretState before = state(endpoint);

        assertThrows(IllegalStateException.class, () -> endpoint.rotateSigningSecret(
                THIRD_SECRET, EXPIRES.minusNanos(1), EXPIRES.plusSeconds(3600)));

        assertEquals(before, state(endpoint));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void rotationAtOrAfterExpiryPreservesMostRecentSecretAsPrevious(long nanosAfterExpiry) {
        WebhookEndpoint endpoint = rotatedEndpoint();
        Instant now = EXPIRES.plusNanos(nanosAfterExpiry);
        Instant nextExpiry = now.plusSeconds(3600);

        endpoint.rotateSigningSecret(THIRD_SECRET, now, nextExpiry);

        assertEquals(THIRD_SECRET, endpoint.getSigningSecret());
        assertEquals(NEXT_SECRET, endpoint.getPreviousSigningSecret());
        assertEquals(nextExpiry, endpoint.getPreviousSigningSecretExpiresAt());
        assertEquals(now, endpoint.getUpdatedAt());
        assertEquals(CREATED, endpoint.getCreatedAt());
        assertTrue(endpoint.hasActivePreviousSigningSecret(now));
    }

    @ParameterizedTest
    @MethodSource("invalidRotationArguments")
    void invalidRotationArgumentsLeaveStateUnchanged(
            String secret, Instant now, Instant expiry, Class<? extends Throwable> expectedType) {
        WebhookEndpoint endpoint = endpoint();
        SecretState before = state(endpoint);

        assertThrows(expectedType, () -> endpoint.rotateSigningSecret(secret, now, expiry));

        assertEquals(before, state(endpoint));
    }

    static Stream<Arguments> invalidRotationArguments() {
        return Stream.of(
                Arguments.of(null, ROTATED, EXPIRES, NullPointerException.class),
                Arguments.of("", ROTATED, EXPIRES, IllegalArgumentException.class),
                Arguments.of(" \t", ROTATED, EXPIRES, IllegalArgumentException.class),
                Arguments.of(TEST_SIGNING_SECRET, ROTATED, EXPIRES, IllegalArgumentException.class),
                Arguments.of(NEXT_SECRET, null, EXPIRES, NullPointerException.class),
                Arguments.of(NEXT_SECRET, ROTATED, null, NullPointerException.class),
                Arguments.of(NEXT_SECRET, ROTATED, ROTATED, IllegalArgumentException.class),
                Arguments.of(NEXT_SECRET, ROTATED, ROTATED.minusNanos(1), IllegalArgumentException.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void activeSecretCheckRequiresTimeWithoutChangingState(boolean rotated) {
        WebhookEndpoint endpoint = rotated ? rotatedEndpoint() : endpoint();
        SecretState before = state(endpoint);

        assertThrows(NullPointerException.class, () -> endpoint.hasActivePreviousSigningSecret(null));

        assertEquals(before, state(endpoint));
    }

    @Test
    void invalidRotationAfterPreviousRotationPreservesBothSecretsAndExpiry() {
        WebhookEndpoint endpoint = rotatedEndpoint();
        SecretState before = state(endpoint);

        assertThrows(IllegalArgumentException.class,
                () -> endpoint.rotateSigningSecret(NEXT_SECRET, EXPIRES, EXPIRES.plusSeconds(3600)));

        assertEquals(before, state(endpoint));
    }

    private WebhookEndpoint endpoint() {
        return new WebhookEndpoint(fixtureTenant, "Orders", "https://example.com/webhook", CREATED, TEST_SIGNING_SECRET);
    }

    private WebhookEndpoint rotatedEndpoint() {
        WebhookEndpoint endpoint = endpoint();
        endpoint.rotateSigningSecret(NEXT_SECRET, ROTATED, EXPIRES);
        return endpoint;
    }

    private SecretState state(WebhookEndpoint endpoint) {
        return new SecretState(endpoint.getSigningSecret(), endpoint.getPreviousSigningSecret(),
                endpoint.getPreviousSigningSecretExpiresAt(), endpoint.getUpdatedAt());
    }

    private record SecretState(String current, String previous, Instant expiry, Instant updatedAt) {
    }

    @Test
    void constructorWithSecretPreservesEndpointFields() {
        Instant now = Instant.parse("2026-10-28T00:00:00Z");
        String secret = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

        WebhookEndpoint endpoint = new WebhookEndpoint(fixtureTenant,
                "Orders", "https://example.com/webhooks", now, secret);

        assertEquals(secret, endpoint.getSigningSecret());
        assertEquals("Orders", endpoint.getName());
        assertEquals("https://example.com/webhooks", endpoint.getUrl());
        assertTrue(endpoint.isEnabled());
        assertEquals(now, endpoint.getCreatedAt());
        assertEquals(now, endpoint.getUpdatedAt());
    }

    @Test
    void constructorWithSecretRejectsNullSecret() {
        assertThrows(NullPointerException.class, () -> new WebhookEndpoint(fixtureTenant,
                "Orders", "https://example.com/webhooks", Instant.now(), null));
    }

    @Test
    void enablingDisabledEndpointUpdatesModificationTimeAndPreservesCreationTime() {
        Instant createdAt = Instant.parse("2026-10-28T00:00:00Z");
        Instant enabledAt = createdAt.plusSeconds(120);
        WebhookEndpoint endpoint = new WebhookEndpoint(fixtureTenant,
                "Orders", "https://example.com/webhooks", createdAt, TEST_SIGNING_SECRET);
        endpoint.changeEnabled(false, createdAt.plusSeconds(60));

        endpoint.changeEnabled(true, enabledAt);

        assertTrue(endpoint.isEnabled());
        assertEquals(enabledAt, endpoint.getUpdatedAt());
        assertEquals(createdAt, endpoint.getCreatedAt());
    }

    @Test
    void disablingAlreadyDisabledEndpointPreservesModificationTime() {
        Instant createdAt = Instant.parse("2026-10-28T00:00:00Z");
        Instant disabledAt = createdAt.plusSeconds(60);
        WebhookEndpoint endpoint = new WebhookEndpoint(fixtureTenant,
                "Orders", "https://example.com/webhooks", createdAt, TEST_SIGNING_SECRET);
        endpoint.changeEnabled(false, disabledAt);

        endpoint.changeEnabled(false, disabledAt.plusSeconds(60));

        assertFalse(endpoint.isEnabled());
        assertEquals(disabledAt, endpoint.getUpdatedAt());
        assertEquals(createdAt, endpoint.getCreatedAt());
    }

    @Test
    void enablingAlreadyEnabledEndpointPreservesModificationTime() {
        Instant createdAt = Instant.parse("2026-10-28T00:00:00Z");
        WebhookEndpoint endpoint = new WebhookEndpoint(fixtureTenant,
                "Order notifications", "https://example.com/webhooks/orders", createdAt, TEST_SIGNING_SECRET);

        endpoint.changeEnabled(true, createdAt.plusSeconds(60));

        assertTrue(endpoint.isEnabled());
        assertEquals(createdAt, endpoint.getUpdatedAt());
        assertEquals(createdAt, endpoint.getCreatedAt());
    }

    @Test
    void disablingEndpointUpdatesModificationTimeAndPreservesCreationTime() {
        Instant createdAt = Instant.parse("2026-10-28T00:00:00Z");
        Instant modifiedAt = createdAt.plusSeconds(60);
        WebhookEndpoint endpoint = new WebhookEndpoint(fixtureTenant,
                "Order notifications", "https://example.com/webhooks/orders", createdAt, TEST_SIGNING_SECRET);

        endpoint.changeEnabled(false, modifiedAt);

        assertFalse(endpoint.isEnabled());
        assertEquals(modifiedAt, endpoint.getUpdatedAt());
        assertEquals(createdAt, endpoint.getCreatedAt());
    }

    @Test
    void newEndpointStartsEnabledWithMatchingTimestamps() {
        Instant now = Instant.parse("2026-10-28T00:00:00Z");

        WebhookEndpoint endpoint = new WebhookEndpoint(fixtureTenant,
                "Order notifications", "https://example.com/webhooks/orders", now, TEST_SIGNING_SECRET);

        assertEquals("Order notifications", endpoint.getName());
        assertEquals("https://example.com/webhooks/orders", endpoint.getUrl());
        assertTrue(endpoint.isEnabled());
        assertEquals(now, endpoint.getCreatedAt());
        assertEquals(now, endpoint.getUpdatedAt());
    }
}
