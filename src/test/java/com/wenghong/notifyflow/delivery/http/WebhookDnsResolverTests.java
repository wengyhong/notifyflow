package com.wenghong.notifyflow.delivery.http;

import java.net.InetAddress;
import java.net.UnknownHostException;

import com.wenghong.notifyflow.endpoint.application.InvalidWebhookUrlException;
import com.wenghong.notifyflow.endpoint.application.WebhookUrlPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class WebhookDnsResolverTests {

    private final WebhookUrlPolicy policy = mock(WebhookUrlPolicy.class);
    private final WebhookDnsResolver resolver = new WebhookDnsResolver(policy);

    @Test
    void returnsOnlyAddressesSuppliedByPolicy() throws Exception {
        InetAddress[] checked = {
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("2606:4700:4700::1111")
        };
        when(policy.resolveAllowedAddresses("example.com")).thenReturn(checked);

        assertArrayEquals(checked, resolver.resolve("example.com"));
        verify(policy).resolveAllowedAddresses("example.com");
        verifyNoMoreInteractions(policy);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Webhook host resolves to a blocked address",
            "Webhook host could not be resolved",
            "Webhook host has no resolved addresses",
            "Webhook host must not be blank"
    })
    void translatesPolicyRejectionAndPreservesCause(String message) throws Exception {
        InvalidWebhookUrlException rejection = new InvalidWebhookUrlException(message);
        when(policy.resolveAllowedAddresses("example.com")).thenThrow(rejection);

        UnknownHostException error = assertThrows(UnknownHostException.class,
                () -> resolver.resolve("example.com"));

        assertEquals("Webhook destination rejected", error.getMessage());
        assertSame(rejection, error.getCause());
        verify(policy).resolveAllowedAddresses("example.com");
        verifyNoMoreInteractions(policy);
    }

    @Test
    void preservesHostnameWithoutAdditionalResolution() throws Exception {
        assertEquals("Example.COM", resolver.resolveCanonicalHostname("Example.COM"));
        verifyNoInteractions(policy);
    }

    @Test
    void portAwareResolutionUsesCheckedAddressesWithoutAnotherLookup() throws Exception {
        InetAddress[] checked = {
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("2606:4700:4700::1111")
        };
        when(policy.resolveAllowedAddresses("example.com")).thenReturn(checked);

        var sockets = resolver.resolve("example.com", 443);

        assertEquals(2, sockets.size());
        for (int index = 0; index < checked.length; index++) {
            assertEquals(checked[index], sockets.get(index).getAddress());
            assertEquals(443, sockets.get(index).getPort());
        }
        verify(policy).resolveAllowedAddresses("example.com");
        verifyNoMoreInteractions(policy);
    }
}
