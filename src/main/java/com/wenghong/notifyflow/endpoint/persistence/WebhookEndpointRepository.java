package com.wenghong.notifyflow.endpoint.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID>{

}
