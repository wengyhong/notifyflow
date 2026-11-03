package com.wenghong.notifyflow.delivery.messaging;

import java.util.UUID;

import com.wenghong.notifyflow.delivery.application.DeliveryProcessingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class DeliveryJobListenerTests {

    @Test
    void receiveProcessesDeliveryId() throws InterruptedException {
        DeliveryProcessingService service = mock(DeliveryProcessingService.class);
        DeliveryJobListener listener = new DeliveryJobListener(service);
        UUID deliveryId = UUID.randomUUID();

        listener.receive(deliveryId.toString());

        verify(service).process(deliveryId);
    }

    @Test
    void receivePropagatesInterruption() throws InterruptedException {
        DeliveryProcessingService service = mock(DeliveryProcessingService.class);
        DeliveryJobListener listener = new DeliveryJobListener(service);
        UUID deliveryId = UUID.randomUUID();
        InterruptedException failure = new InterruptedException("Worker interrupted");

        doThrow(failure).when(service).process(deliveryId);

        InterruptedException thrown = assertThrows(
                InterruptedException.class,
                () -> listener.receive(deliveryId.toString()));

        assertSame(failure, thrown);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not-a-uuid", "a8a1d32a-a014-47bb-bf82-f31ea55e4acg"})
    void receiveRejectsMalformedDeliveryIdWithoutCallingService(String message) {
        DeliveryProcessingService service = mock(DeliveryProcessingService.class);
        DeliveryJobListener listener = new DeliveryJobListener(service);

        assertThrows(IllegalArgumentException.class, () -> listener.receive(message));

        verifyNoInteractions(service);
    }

    @Test
    void receivePropagatesProcessingFailure() throws InterruptedException {
        DeliveryProcessingService service = mock(DeliveryProcessingService.class);
        DeliveryJobListener listener = new DeliveryJobListener(service);
        UUID deliveryId = UUID.randomUUID();
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("Database unavailable");
        doThrow(failure).when(service).process(deliveryId);

        DataAccessResourceFailureException thrown = assertThrows(
                DataAccessResourceFailureException.class,
                () -> listener.receive(deliveryId.toString()));

        assertSame(failure, thrown);
        verify(service).process(deliveryId);
    }
}
