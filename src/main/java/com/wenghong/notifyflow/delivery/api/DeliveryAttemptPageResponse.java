package com.wenghong.notifyflow.delivery.api;

import java.util.List;

public record DeliveryAttemptPageResponse(
        List<DeliveryAttemptResponse> content, int page, int size, long totalElements, int totalPages) {
}
