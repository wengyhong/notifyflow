package com.wenghong.notifyflow.identity.api;

import java.util.List;
import java.util.UUID;

public record CurrentUserResponse(
        UUID id,
        String email,
        List<WorkspaceResponse> workspaces) {
}
