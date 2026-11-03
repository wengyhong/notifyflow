package com.wenghong.notifyflow.delivery.configuration;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration (proxyBeanMethods = false)
public class WebhookHttpConfiguration {

    @Bean
    public HttpClient webhookHttpClient()
    {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
}
