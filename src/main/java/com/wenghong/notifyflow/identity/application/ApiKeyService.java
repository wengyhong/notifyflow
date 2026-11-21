package com.wenghong.notifyflow.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wenghong.notifyflow.identity.domain.ApiKey;
import com.wenghong.notifyflow.identity.persistence.ApiKeyRepository;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;

@Service
public class ApiKeyService {

    private final ApiKeyRepository keys;
    private final TenantRepository tenantRepository;
    private final ApiKeyGenerator generator;
    private final ApiKeyHasher hasher;
    private final Clock clock;

    public ApiKeyService(ApiKeyRepository keys, TenantRepository tenantRepository, ApiKeyGenerator generator,
            ApiKeyHasher hasher, Clock clock) {
        this.keys = keys;
        this.tenantRepository = tenantRepository;
        this.generator = generator;
        this.hasher = hasher;
        this.clock = clock;
    }

    @Transactional
    public ApiKeyCreationResult create(TenantAccess access, String name) {

        access.requireAdmin();
        Instant now = clock.instant();

        var tenant = tenantRepository.getReferenceById(access.tenantId());

        String rawKey = generator.generate();
        String hash = hasher.hash(rawKey);

        ApiKey key = keys.save(new ApiKey(tenant, name, hash, now));

        return new ApiKeyCreationResult(key.getId(), key.getName(), rawKey, key.getCreatedAt());
    }

    @Transactional
    public boolean revoke(TenantAccess access, UUID id) {
        access.requireAdmin();

        return keys.findByIdAndTenant_IdForUpdate(id, access.tenantId()).map(key -> {
            key.revoke(clock.instant());

            return true;
        }).orElse(false);
    }

}
