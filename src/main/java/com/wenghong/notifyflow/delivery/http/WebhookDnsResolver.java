package com.wenghong.notifyflow.delivery.http;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.apache.hc.client5.http.DnsResolver;
import org.springframework.stereotype.Component;

import com.wenghong.notifyflow.endpoint.application.InvalidWebhookUrlException;
import com.wenghong.notifyflow.endpoint.application.WebhookUrlPolicy;

@Component
public class WebhookDnsResolver implements DnsResolver {

    private final WebhookUrlPolicy policy;

    public WebhookDnsResolver(WebhookUrlPolicy policy) {
        this.policy = policy;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        try {
            return policy.resolveAllowedAddresses(host);
        } catch (InvalidWebhookUrlException exception) {
            UnknownHostException failure =
                    new UnknownHostException("Webhook destination rejected");
            failure.initCause(exception);
            throw failure;
        }
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        return host;
    }
}