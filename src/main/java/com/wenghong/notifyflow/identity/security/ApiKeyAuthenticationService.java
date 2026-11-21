package com.wenghong.notifyflow.identity.security;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wenghong.notifyflow.identity.application.ApiKeyHasher;
import com.wenghong.notifyflow.identity.persistence.ApiKeyRepository;

@Service
public class ApiKeyAuthenticationService {

    private final ApiKeyHasher hasher;
    private final ApiKeyRepository keys;

    public ApiKeyAuthenticationService(ApiKeyHasher hasher, ApiKeyRepository keys) {
        this.hasher = hasher;
        this.keys = keys;
    }

    @Transactional (readOnly = true)

    public Optional<ApiKeyPrincipal> authenticate(String rawKey) {
        if (rawKey == null
                || !rawKey.matches("nf_[A-Za-z0-9_-]{43}")) {
            return Optional.empty();
        }

        String hash = hasher.hash(rawKey);

        return keys.findByKeyHashAndRevokedAtIsNull(hash)
                .map(key -> new ApiKeyPrincipal(key.getId(), key.getTenant().getId()));
    }
}
