package com.wenghong.notifyflow.identity.configuration;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration(proxyBeanMethods = false)
@Profile("local")
public class LocalManagementSecurityConfiguration {

    @Bean
    @Order(0)
    public SecurityFilterChain localManagementSecurityFilterChain(HttpSecurity http) throws Exception {

        RequestMatcher reachableEndpoints = EndpointRequest.to("health", "prometheus");

        http.securityMatcher(EndpointRequest.toAnyEndpoint());

        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(request -> "GET".equals(request.getMethod()) && reachableEndpoints.matches(request))
                .permitAll()
                .anyRequest().denyAll());

        http.sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        return http.build();

    }
}
