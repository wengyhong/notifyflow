package com.wenghong.notifyflow.delivery.http;

import java.util.UUID;
import java.time.Instant;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebhookSignerTests {

    private static final String CURRENT_SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String PREVIOUS_SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final UUID ID = UUID.fromString("a92c7fcd-8e89-4ce0-9baf-745b7362dd50");
    private static final Instant SENT_AT = Instant.ofEpochSecond(1794630000L);
    private static final String BODY = "{\"orderId\":128}";
    private static final String CURRENT_SIGNATURE = "v1,7CclEDnWI2heBWWpeu+ugiW3rfNe+wwTkFraomzGBu4=";
    private static final String PREVIOUS_SIGNATURE = "v1,yHO3jowv4qSYgTkqAJkR59UAYBgI+nqqT0Ixfq/2ZTA=";
    private final WebhookSigner signer = new WebhookSigner();

    @Test
    void activeOverlapReturnsCurrentThenPreviousSignatureWithoutRawSecrets() throws Exception {
        String header = signer.signWithRotation(CURRENT_SECRET, PREVIOUS_SECRET,
                SENT_AT.plusSeconds(3600), ID, SENT_AT, BODY);

        // Independent Python HMAC-SHA256 reference values for both keys.
        assertEquals(CURRENT_SIGNATURE + " " + PREVIOUS_SIGNATURE, header);
        assertEquals(2, header.split(" ").length);
        assertFalse(header.contains(CURRENT_SECRET));
        assertFalse(header.contains(PREVIOUS_SECRET));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 1})
    void checksOverlapAgainstExactSendTime(long expiryNanosAfterSendTime) throws Exception {
        String header = signer.signWithRotation(CURRENT_SECRET, PREVIOUS_SECRET,
                SENT_AT.plusNanos(expiryNanosAfterSendTime), ID, SENT_AT, BODY);

        String expected = expiryNanosAfterSendTime > 0
                ? CURRENT_SIGNATURE + " " + PREVIOUS_SIGNATURE : CURRENT_SIGNATURE;
        assertEquals(expected, header);
    }

    @ParameterizedTest
    @MethodSource("absentPreviousSecretMetadata")
    void missingPreviousSecretOrExpiryProducesOnlyCurrentSignature(String previous, Instant expiry)
            throws Exception {
        assertEquals(CURRENT_SIGNATURE,
                signer.signWithRotation(CURRENT_SECRET, previous, expiry, ID, SENT_AT, BODY));
    }

    static Stream<Arguments> absentPreviousSecretMetadata() {
        return Stream.of(
                Arguments.of(null, null),
                Arguments.of(PREVIOUS_SECRET, null),
                Arguments.of(null, SENT_AT.plusSeconds(3600)));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    void expiredPreviousSecretIsNotDecodedOrUsed(long expiryNanosAfterSendTime) throws Exception {
        assertEquals(CURRENT_SIGNATURE,
                signer.signWithRotation(CURRENT_SECRET, "invalid-base64-secret",
                        SENT_AT.plusNanos(expiryNanosAfterSendTime), ID, SENT_AT, BODY));
    }

    @Test
    void doesNotFallBackToPreviousSecretWhenCurrentSecretIsInvalid() {
        assertThrows(IllegalArgumentException.class,
                () -> signer.signWithRotation("invalid-base64-secret", PREVIOUS_SECRET,
                        SENT_AT.plusSeconds(3600), ID, SENT_AT, BODY));
    }

    @Test
    void signsKnownPayload() throws Exception {
        WebhookSigner signer = new WebhookSigner();

        String signature = signer.sign(
                "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                UUID.fromString("a92c7fcd-8e89-4ce0-9baf-745b7362dd50"),
                1794630000L,
                "{\"orderId\":128}");

        // Reference value calculated independently using .NET HMACSHA256.
        assertEquals(
                "v1,7CclEDnWI2heBWWpeu+ugiW3rfNe+wwTkFraomzGBu4=",
                signature);
    }
}
