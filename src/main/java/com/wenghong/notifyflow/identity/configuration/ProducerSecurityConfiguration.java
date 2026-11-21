package com.wenghong.notifyflow.identity.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

import com.wenghong.notifyflow.identity.security.ApiKeyAuthenticationFilter;
import com.wenghong.notifyflow.identity.security.ApiKeyAuthenticationService;

@Configuration(proxyBeanMethods = false)
public class ProducerSecurityConfiguration {

    @Bean
    @Order(1)
    public SecurityFilterChain producerSecurityFilterChain(
            HttpSecurity http,
            ApiKeyAuthenticationService authenticationService)
            throws Exception {
        http.securityMatcher("/api/producer/**");
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        http.csrf(AbstractHttpConfigurer::disable);
        http.formLogin(AbstractHttpConfigurer::disable);
        http.httpBasic(AbstractHttpConfigurer::disable);
        http.logout(AbstractHttpConfigurer::disable);
        http.requestCache(cache -> cache.disable());

        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.POST, "/api/producer/events")
                .hasAuthority("EVENT_PUBLISH")
                .anyRequest().denyAll());

        http.exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));

        http.addFilterBefore(
                new ApiKeyAuthenticationFilter(authenticationService),
                AnonymousAuthenticationFilter.class);

        return http.build();
    }
}
