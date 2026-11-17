package com.wenghong.notifyflow.endpoint.application;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WebhookUrlPolicy {

    private final WebhookHostResolver resolver;
    private final WebhookAddressPolicy addressPolicy;
    private final boolean allowLoopback;

    public WebhookUrlPolicy(
            WebhookHostResolver resolver,
            WebhookAddressPolicy addressPolicy,
            @Value("${notifyflow.webhook.allow-loopback:false}") boolean allowLoopback) {
        this.resolver = resolver;
        this.addressPolicy = addressPolicy;
        this.allowLoopback = allowLoopback;
    }

    public URI validate(String url) {
        URI uri;
        if (url == null || url.isBlank()) {
            throw new InvalidWebhookUrlException("Webhook URL must not be blank");
        }

        try {

            uri = new URI(url);

        } catch (URISyntaxException exception) {
            throw new InvalidWebhookUrlException(
                    "Webhook URL is malformed");
        }

        String scheme = uri.getScheme();

        if (!"http".equalsIgnoreCase(scheme)
                && !"https".equalsIgnoreCase(scheme)) {
            throw new InvalidWebhookUrlException(
                    "Webhook URL must use HTTP or HTTPS");
        }

        if (uri.getRawUserInfo() != null) {
            throw new InvalidWebhookUrlException("Webhook URL must not contain credentials");
        }

        if (uri.getHost() == null) {
            throw new InvalidWebhookUrlException("Webhook URL must contain a valid host");
        }

        if (uri.getRawFragment() != null) {
            throw new InvalidWebhookUrlException("Webhook URL must not contain a fragment");
        }
        int port = uri.getPort();

        if (port != -1 && (port < 1 || port > 65535)) {
            throw new InvalidWebhookUrlException(
                    "Webhook URL port must be between 1 and 65535");

        }

        resolveAllowedAddresses(uri.getHost());

        return uri;

    }

    public InetAddress[] resolveAllowedAddresses(String host) {
        if (host == null || host.isBlank()) {
            throw new InvalidWebhookUrlException(
                    "Webhook host must not be blank");
        }

        InetAddress[] addresses;

        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException exception) {
            throw new InvalidWebhookUrlException(
                    "Webhook host could not be resolved");
        }

        if (addresses.length == 0) {
            throw new InvalidWebhookUrlException(
                    "Webhook host has no resolved addresses");
        }

        for (InetAddress address : addresses) {

            if (allowLoopback && address.isLoopbackAddress()) {
                continue;
            }
            if (addressPolicy.isBlocked(address)) {
                throw new InvalidWebhookUrlException(
                        "Webhook host resolves to a blocked address");
            }
        }

        return addresses;
    }
    // Enable it when NotifyFlow needs to send webhooks to a receiver running on the
    // same machine, such as your current demo:
    // http://127.0.0.1:1786/webhook
    // Use this rule:
    // Destination Enable loopback?
    // Local receiver at 127.0.0.1 or ::1 Yes, for development
    // Public webhook URL No
    // Production deployment Keep disabled
    // Another Docker container No—loopback points to NotifyFlow’s own container
}
