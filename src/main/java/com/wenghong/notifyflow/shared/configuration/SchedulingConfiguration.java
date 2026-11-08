package com.wenghong.notifyflow.shared.configuration;

import java.util.Random;
import java.util.random.RandomGenerator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration (proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfiguration {


    @Bean
    RandomGenerator retryRandomGenerator()
    {
        return new Random();
    }
}
