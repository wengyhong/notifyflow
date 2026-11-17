package com.wenghong.notifyflow.endpoint.application;

import java.net.URI;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.api.Test;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class WebhookUrlPolicyTests {

    private final WebhookHostResolver resolver = mock(WebhookHostResolver.class);
    private final WebhookUrlPolicy policy = new WebhookUrlPolicy(resolver, new WebhookAddressPolicy(), false);

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\r\n"})
    void directResolutionRejectsMissingHostBeforeDnsLookup(String host) {
        InvalidWebhookUrlException error = assertThrows(InvalidWebhookUrlException.class,
                () -> policy.resolveAllowedAddresses(host));

        assertEquals("Webhook host must not be blank", error.getMessage());
        verifyNoInteractions(resolver);
    }

    @Test
    void directResolutionReturnsCheckedAnswersFromOneLookup() throws Exception {
        InetAddress[] resolved = {
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("2606:4700:4700::1111")
        };
        when(resolver.resolve("example.com")).thenReturn(resolved);

        assertArrayEquals(resolved, policy.resolveAllowedAddresses("example.com"));
        verify(resolver).resolve("example.com");
        verifyNoMoreInteractions(resolver);
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.255.255.255", "::1"})
    void acceptsLoopbackOnlyWhenEnabled(String literal) throws Exception {
        when(resolver.resolve("localhost")).thenReturn(new InetAddress[] {
                InetAddress.getByName(literal)
        });
        WebhookUrlPolicy localPolicy = new WebhookUrlPolicy(resolver, new WebhookAddressPolicy(), true);
        String url = "http://localhost:1786/webhook";

        assertThrows(InvalidWebhookUrlException.class, () -> policy.validate(url));
        assertEquals(URI.create(url), localPolicy.validate(url));
    }

    @Test
    void enabledExceptionAcceptsBothIpv4AndIpv6LoopbackAnswers() throws Exception {
        when(resolver.resolve("localhost")).thenReturn(new InetAddress[] {
                InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1")
        });
        WebhookUrlPolicy localPolicy = new WebhookUrlPolicy(resolver, new WebhookAddressPolicy(), true);

        assertEquals(URI.create("http://localhost:1786/webhook"),
                localPolicy.validate("http://localhost:1786/webhook"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.1", "192.168.1.10", "169.254.169.254", "fd00::1", "0.0.0.0"})
    void enabledLoopbackExceptionDoesNotPermitOtherBlockedAddresses(String literal) throws Exception {
        when(resolver.resolve("example.com")).thenReturn(new InetAddress[] {
                InetAddress.getByName(literal)
        });
        WebhookUrlPolicy localPolicy = new WebhookUrlPolicy(resolver, new WebhookAddressPolicy(), true);

        assertThrows(InvalidWebhookUrlException.class,
                () -> localPolicy.validate("http://example.com/webhook"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void enabledLoopbackExceptionStillChecksOtherAnswers(boolean loopbackFirst) throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        InetAddress privateAddress = InetAddress.getByName("10.0.0.1");
        when(resolver.resolve("example.com")).thenReturn(loopbackFirst
                ? new InetAddress[] {loopback, privateAddress}
                : new InetAddress[] {privateAddress, loopback});
        WebhookUrlPolicy localPolicy = new WebhookUrlPolicy(resolver, new WebhookAddressPolicy(), true);

        assertThrows(InvalidWebhookUrlException.class,
                () -> localPolicy.validate("http://example.com/webhook"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com",
            "https://example.com/webhook",
            "HTTP://example.com/webhook",
            "hTtPs://example.com/webhook",
            "https://example.com:443/webhook",
            "http://example.com:1/webhook",
            "https://example.com:65535/webhook",
            "https://example.com/webhooks/order%20confirmed?source=orders&version=1",
            "https://example.com/webhook?next=https%3A%2F%2Fexample.org%2Fpath",
            "https://[2001:4860:4860::8888]:443/webhook"
    })
    void acceptsHttpAndHttpsAndPreservesUri(String url) throws Exception {
        String host = URI.create(url).getHost();
        when(resolver.resolve(host)).thenReturn(new InetAddress[] {
                InetAddress.getByAddress(new byte[] {8, 8, 8, 8})
        });

        URI result = policy.validate(url);

        assertEquals(URI.create(url), result);
        assertEquals(url, result.toString());
        verify(resolver).resolve(host);
    }

    @Test
    void acceptsMultiplePublicIpv4AndIpv6Answers() throws Exception {
        when(resolver.resolve("example.com")).thenReturn(new InetAddress[] {
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("2606:4700:4700::1111")
        });

        assertEquals(URI.create("https://example.com/webhook"),
                policy.validate("https://example.com/webhook"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "169.254.169.254", "10.0.0.1", "fd00::1"})
    void rejectsBlockedDnsAnswer(String literal) throws Exception {
        when(resolver.resolve("example.com")).thenReturn(new InetAddress[] {
                InetAddress.getByName(literal)
        });

        assertDnsRejected("Webhook host resolves to a blocked address");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsMixedPublicAndBlockedAnswersInEitherOrder(boolean blockedFirst) throws Exception {
        InetAddress publicAddress = InetAddress.getByName("8.8.8.8");
        InetAddress blockedAddress = InetAddress.getByName("::1");
        when(resolver.resolve("example.com")).thenReturn(blockedFirst
                ? new InetAddress[] {blockedAddress, publicAddress}
                : new InetAddress[] {publicAddress, blockedAddress});

        assertDnsRejected("Webhook host resolves to a blocked address");
    }

    @Test
    void rejectsEmptyDnsAnswer() throws Exception {
        when(resolver.resolve("example.com")).thenReturn(new InetAddress[0]);

        assertDnsRejected("Webhook host has no resolved addresses");
    }

    @Test
    void translatesDnsFailureWithoutExposingResolverDetails() throws Exception {
        when(resolver.resolve("example.com"))
                .thenThrow(new UnknownHostException("Internal resolver diagnostic"));

        assertDnsRejected("Webhook host could not be resolved");
    }

    @Test
    void asksResolverAgainWhenValidatingSameUrlAgain() throws Exception {
        when(resolver.resolve("example.com"))
                .thenReturn(new InetAddress[] {InetAddress.getByName("8.8.8.8")})
                .thenReturn(new InetAddress[] {InetAddress.getByName("127.0.0.1")});

        assertEquals(URI.create("https://example.com/webhook"),
                policy.validate("https://example.com/webhook"));
        assertThrows(InvalidWebhookUrlException.class,
                () -> policy.validate("https://example.com/webhook"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\r\n"})
    void rejectsMissingOrBlankUrl(String url) {
        assertRejected(url, "Webhook URL must not be blank");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/bad path",
            "https://example.com/%ZZ",
            "https://[not-an-ip]/webhook",
            "https://",
            " https://example.com/webhook"
    })
    void translatesUriSyntaxErrors(String url) {
        assertRejected(url, "Webhook URL is malformed");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com/webhook",
            "file:///etc/passwd",
            "gopher://example.com/webhook",
            "javascript:alert(1)",
            "mailto:someone@example.com",
            "//example.com/webhook",
            "/relative/webhook",
            "example.com"
    })
    void rejectsUnsupportedOrMissingScheme(String url) {
        assertRejected(url, "Webhook URL must use HTTP or HTTPS");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https:/webhook",
            "https:///webhook",
            "http:example.com/webhook",
            "https://bad_host.example/webhook",
            "https://example.com:not-a-port/webhook"
    })
    void rejectsMissingOrInvalidHost(String url) {
        assertRejected(url, "Webhook URL must contain a valid host");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://user:password@example.com/webhook",
            "https://user@example.com/webhook",
            "https://@example.com/webhook",
            "https://user:p%40ssword@example.com/webhook"
    })
    void rejectsCredentialsIncludingEmptyOrEncodedUserInfo(String url) {
        assertRejected(url, "Webhook URL must not contain credentials");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/webhook#section",
            "https://example.com/webhook#",
            "https://example.com/webhook?source=orders#section"
    })
    void rejectsFragmentsIncludingEmptyFragment(String url) {
        assertRejected(url, "Webhook URL must not contain a fragment");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 65536, 2147483647})
    void rejectsExplicitPortsOutsideAllowedRange(int port) {
        assertRejected("https://example.com:" + port + "/webhook",
                "Webhook URL port must be between 1 and 65535");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://user:top-secret@example.com/webhook",
            "https://user:top-secret@example.com/bad path"
    })
    void rejectionMessageDoesNotExposeSubmittedUrlOrCredentials(String url) {
        InvalidWebhookUrlException error = assertThrows(
                InvalidWebhookUrlException.class, () -> policy.validate(url));

        assertFalse(error.getMessage().contains(url));
        assertFalse(error.getMessage().contains("top-secret"));
        assertFalse(error.getMessage().contains("user"));
        verifyNoInteractions(resolver);
    }

    private void assertRejected(String url, String expectedMessage) {
        InvalidWebhookUrlException error = assertThrows(
                InvalidWebhookUrlException.class, () -> policy.validate(url));
        assertEquals(expectedMessage, error.getMessage());
        verifyNoInteractions(resolver);
    }

    private void assertDnsRejected(String expectedMessage) throws Exception {
        InvalidWebhookUrlException error = assertThrows(InvalidWebhookUrlException.class,
                () -> policy.validate("https://example.com/webhook"));
        assertEquals(expectedMessage, error.getMessage());
        verify(resolver).resolve("example.com");
    }
}
