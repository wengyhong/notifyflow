package com.wenghong.notifyflow.endpoint.api;

import java.util.List;

public record WebhookEndpointPageResponse(List<WebhookEndpointResponse> content, int page, int size,
                long totalElements,
                int totalPages) {

}
