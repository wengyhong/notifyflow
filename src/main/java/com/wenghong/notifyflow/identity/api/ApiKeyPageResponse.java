package com.wenghong.notifyflow.identity.api;

import java.util.List;

public record ApiKeyPageResponse(
        List<ApiKeyResponse> content, int page, int size, long totalElements, int totalPages) {
}
