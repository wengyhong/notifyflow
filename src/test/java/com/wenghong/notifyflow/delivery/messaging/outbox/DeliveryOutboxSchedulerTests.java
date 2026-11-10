package com.wenghong.notifyflow.delivery.messaging.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DeliveryOutboxSchedulerTests {

    private final DeliveryOutboxRelay relay = mock(DeliveryOutboxRelay.class);
    private final DeliveryOutboxScheduler scheduler = new DeliveryOutboxScheduler(relay);

    @Test
    void emptyOutboxStopsAfterFirstCheck() {
        when(relay.publishNext()).thenReturn(false);

        scheduler.publishPending();

        verify(relay).publishNext();
        verifyNoMoreInteractions(relay);
    }

    @Test
    void publishesAvailableMessagesThenStopsWhenNoneRemain() {
        when(relay.publishNext()).thenReturn(true, true, false);

        scheduler.publishPending();

        verify(relay, times(3)).publishNext();
        verifyNoMoreInteractions(relay);
    }

    @Test
    void stopsAfterOneHundredMessagesEvenWhenMoreAreAvailable() {
        when(relay.publishNext()).thenReturn(true);

        scheduler.publishPending();

        verify(relay, times(100)).publishNext();
        verifyNoMoreInteractions(relay);
    }

    @Test
    void relayFailureStopsCurrentRunAndLaterRunCanResume() {
        AmqpException failure = new AmqpException("Broker unavailable");
        when(relay.publishNext()).thenReturn(true).thenThrow(failure).thenReturn(true, false);

        AmqpException thrown = assertThrows(AmqpException.class, scheduler::publishPending);

        assertSame(failure, thrown);
        verify(relay, times(2)).publishNext();

        scheduler.publishPending();

        verify(relay, times(4)).publishNext();
        verifyNoMoreInteractions(relay);
    }
}
