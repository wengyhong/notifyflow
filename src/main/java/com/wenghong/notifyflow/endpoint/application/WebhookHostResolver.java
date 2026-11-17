package com.wenghong.notifyflow.endpoint.application;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.springframework.stereotype.Component;

@Component
public class WebhookHostResolver {

    public InetAddress[] resolve(String host)

            throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }

}
