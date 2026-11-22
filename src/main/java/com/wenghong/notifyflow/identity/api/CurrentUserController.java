package com.wenghong.notifyflow.identity.api;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wenghong.notifyflow.identity.application.CurrentUserService;
import com.wenghong.notifyflow.identity.security.AppUserDetails;

@RestController
public class CurrentUserController {

    private final CurrentUserService service;

    public CurrentUserController(CurrentUserService service) {
        this.service = service;
    }

    @GetMapping("/api/auth/me")
    public ResponseEntity<CurrentUserResponse> currentUser(
            @AuthenticationPrincipal AppUserDetails user) {
        CurrentUserResponse response = new CurrentUserResponse(
                user.getUserId(),
                user.getUsername(),
                service.findWorkspaces(user.getUserId()));

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }
}
