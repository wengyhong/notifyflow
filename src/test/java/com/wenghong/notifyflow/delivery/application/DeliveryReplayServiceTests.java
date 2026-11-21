package com.wenghong.notifyflow.delivery.application;

import java.util.UUID;

import com.wenghong.notifyflow.delivery.persistence.DeliveryAttemptRepository;
import com.wenghong.notifyflow.delivery.persistence.DeliveryRepository;
import com.wenghong.notifyflow.identity.application.TenantAccess;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DeliveryReplayServiceTests {
    @Test
    void viewerIsDeniedBeforeStartingATransactionOrReadingAttemptHistory() {
        var deliveries = mock(DeliveryRepository.class);
        var attempts = mock(DeliveryAttemptRepository.class);
        var transactions = mock(TransactionTemplate.class);
        var publisher = mock(ApplicationEventPublisher.class);
        var service = new DeliveryReplayService(deliveries, attempts, transactions, publisher);

        assertThrows(AccessDeniedException.class, () -> service.replay(
                new TenantAccess(UUID.randomUUID(), TenantRole.VIEWER), UUID.randomUUID()));
        verifyNoInteractions(deliveries, attempts, transactions, publisher);
    }
}
