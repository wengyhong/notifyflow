package com.wenghong.notifyflow.identity.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertFalse;

class LocalManagementSecurityConfigurationTests {
    @Test
    void localManagementChainIsNotRegisteredOutsideTheLocalProfile() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .withUserConfiguration(LocalManagementSecurityConfiguration.class)
                .run(context -> assertFalse(context.containsBean("localManagementSecurityFilterChain")));
    }
}
