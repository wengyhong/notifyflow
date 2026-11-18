package com.wenghong.notifyflow.delivery.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration (proxyBeanMethods = false)
public class DeliveryRateLimitConfiguration {

    @Bean
    public RedisScript<Long> deliveryRateLimitScript(){
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();

        script.setLocation(new ClassPathResource("redis/delivery-rate-limit.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
