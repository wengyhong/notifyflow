package com.wenghong.notifyflow.delivery.api;

import java.util.List;

public record DeliveryPageResponse(
        List<DeliveryResponse> content, int page, int size, long totalElements, int totalPages) {
}
