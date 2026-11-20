package com.wenghong.notifyflow.identity.security;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.userdetails.User;

import com.wenghong.notifyflow.identity.domain.AppUser;

public class AppUserDetails extends User {

    private static final long serialVersionUID = 1L;

    private final UUID userId;

    public AppUserDetails(AppUser user)
    {
        super(user.getEmail(), user.getPasswordHash(), user.isEnabled(), true, true, true, List.of());

        this.userId = user.getId();
    }

    public UUID getUserId(){
        return userId;
    }
}
