package com.wenghong.notifyflow.endpoint.application;

import java.net.InetAddress;
import java.net.URI;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookUrlPolicyConfigurationTests {

    private static final String URL = "http://localhost:1786/webhook";
    private final WebhookHostResolver resolver = mock(WebhookHostResolver.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(WebhookHostResolver.class, () -> resolver)
            .withUserConfiguration(WebhookUrlPolicy.class, WebhookAddressPolicy.class);

    @BeforeEach
    void resolveLoopbackWithoutLiveDns() throws Exception {
        when(resolver.resolve("localhost")).thenReturn(new InetAddress[] {
                InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1")
        });
    }

    @Test
    void absentPropertyDefaultsToBlockingLoopback() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            WebhookUrlPolicy policy = context.getBean(WebhookUrlPolicy.class);
            assertThrows(InvalidWebhookUrlException.class, () -> policy.validate(URL));
        });
    }

    @Test
    void baseConfigurationWithoutLocalProfileBlocksLoopback() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=policy-test")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    WebhookUrlPolicy policy = context.getBean(WebhookUrlPolicy.class);
                    assertThrows(InvalidWebhookUrlException.class, () -> policy.validate(URL));
                });
    }

    @Test
    void localProfileLoadsLoopbackExceptionFromApplicationLocalProperties() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=local")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals("true", context.getEnvironment()
                            .getProperty("notifyflow.webhook.allow-loopback"));
                    assertEquals(URI.create(URL), context.getBean(WebhookUrlPolicy.class).validate(URL));
                });
    }

    @Test
    void explicitFalseOverridesLocalProfileException() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=local", "notifyflow.webhook.allow-loopback=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    WebhookUrlPolicy policy = context.getBean(WebhookUrlPolicy.class);
                    assertThrows(InvalidWebhookUrlException.class, () -> policy.validate(URL));
                });
    }
}
