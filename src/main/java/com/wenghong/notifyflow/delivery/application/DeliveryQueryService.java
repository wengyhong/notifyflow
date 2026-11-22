package com.wenghong.notifyflow.delivery.application;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wenghong.notifyflow.delivery.api.DeliveryAttemptResponse;
import com.wenghong.notifyflow.delivery.api.DeliveryResponse;
import com.wenghong.notifyflow.delivery.domain.Delivery;
import com.wenghong.notifyflow.delivery.domain.DeliveryStatus;
import com.wenghong.notifyflow.delivery.persistence.DeliveryAttemptRepository;
import com.wenghong.notifyflow.delivery.persistence.DeliveryRepository;
import com.wenghong.notifyflow.identity.application.TenantAccess;

@Service
@Transactional(readOnly = true)
public class DeliveryQueryService {
    private final DeliveryRepository deliveries;
    private final DeliveryAttemptRepository attempts;

    public DeliveryQueryService(DeliveryRepository deliveries, DeliveryAttemptRepository attempts) {
        this.deliveries = deliveries;
        this.attempts = attempts;
    }

    public Page<DeliveryResponse> findAll(TenantAccess access, DeliveryStatus status, Pageable pageable) {
        return deliveries.findHistoryByTenantId(access.tenantId(), status, pageable).map(this::toResponse);
    }

    public Optional<DeliveryResponse> findById(TenantAccess access, UUID id) {
        return deliveries.findByIdAndTenantId(id, access.tenantId()).map(this::toResponse);
    }

    public Optional<Page<DeliveryAttemptResponse>> findAttempts(TenantAccess access, UUID id, Pageable pageable) {
        return deliveries.findByIdAndTenantId(id, access.tenantId())
                .map(delivery -> attempts.findHistoryByDeliveryIdAndTenantId(id, access.tenantId(), pageable)
                        .map(attempt -> new DeliveryAttemptResponse(
                                attempt.getId(), id, attempt.getAttemptNumber(), attempt.getStartedAt(),
                                attempt.getCompletedAt(), attempt.getResponseStatus(), attempt.getErrorMessage())));
    }

    private DeliveryResponse toResponse(Delivery delivery) {
        return new DeliveryResponse(delivery.getId(), delivery.getEvent().getId(), delivery.getEndpoint().getId(),
                delivery.getStatus(), delivery.getCreatedAt(), delivery.getNextAttemptAt(),
                delivery.getRetryCycleStartAttempt());
    }
}
