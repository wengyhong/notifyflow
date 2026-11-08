package com.wenghong.notifyflow.delivery.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.wenghong.notifyflow.delivery.domain.DeliveryStatus;
import com.wenghong.notifyflow.delivery.messaging.DeliveryJobPublisher;
import com.wenghong.notifyflow.delivery.persistence.DeliveryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DeliveryRetrySchedulerTests {

    @Test
    void emptyBatchPublishesNothing() {
        Instant now = Instant.parse("2026-11-02T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        DeliveryRepository repository = mock(DeliveryRepository.class);
        DeliveryJobPublisher publisher = mock(DeliveryJobPublisher.class);
        DeliveryRetryScheduler scheduler = new DeliveryRetryScheduler(clock, publisher, repository);
        PageRequest batch = PageRequest.of(0, 100);
        when(repository.findDueRetryIds(DeliveryStatus.RETRY_SCHEDULED, now, batch))
                .thenReturn(List.of());

        scheduler.publishDueRetries();

        verify(repository).findDueRetryIds(DeliveryStatus.RETRY_SCHEDULED, now, batch);
        verifyNoInteractions(publisher);
    }

    @Test
    void repositoryFailurePropagatesWithoutPublishingJobs() {
        Instant now = Instant.parse("2026-11-02T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        DeliveryRepository repository = mock(DeliveryRepository.class);
        DeliveryJobPublisher publisher = mock(DeliveryJobPublisher.class);
        DeliveryRetryScheduler scheduler = new DeliveryRetryScheduler(clock, publisher, repository);
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("Database unavailable");
        when(repository.findDueRetryIds(
                DeliveryStatus.RETRY_SCHEDULED, now, PageRequest.of(0, 100)))
                .thenThrow(failure);

        DataAccessResourceFailureException thrown = assertThrows(
                DataAccessResourceFailureException.class, scheduler::publishDueRetries);

        assertSame(failure, thrown);
        verifyNoInteractions(publisher);
    }

    @Test
    void publisherFailurePropagatesAndStopsCurrentBatch() {
        Instant now = Instant.parse("2026-11-02T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        DeliveryRepository repository = mock(DeliveryRepository.class);
        DeliveryJobPublisher publisher = mock(DeliveryJobPublisher.class);
        DeliveryRetryScheduler scheduler = new DeliveryRetryScheduler(clock, publisher, repository);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        PageRequest batch = PageRequest.of(0, 100);
        when(repository.findDueRetryIds(DeliveryStatus.RETRY_SCHEDULED, now, batch))
                .thenReturn(List.of(first, second, third));
        AmqpException failure = new AmqpException("Broker unavailable");
        doThrow(failure).when(publisher).publish(second);

        AmqpException thrown = assertThrows(AmqpException.class, scheduler::publishDueRetries);

        assertSame(failure, thrown);
        verify(publisher).publish(first);
        verify(publisher).publish(second);
        verifyNoMoreInteractions(publisher);
        // Publishing does not update delivery state; the next poll can find these IDs again.
        verify(repository).findDueRetryIds(DeliveryStatus.RETRY_SCHEDULED, now, batch);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void publishesEachDueDeliveryId() {
        Instant now = Instant.parse("2026-11-02T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        DeliveryRepository repository = mock(DeliveryRepository.class);
        DeliveryJobPublisher publisher = mock(DeliveryJobPublisher.class);
        DeliveryRetryScheduler scheduler = new DeliveryRetryScheduler(clock, publisher, repository);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        PageRequest batch = PageRequest.of(0, 100);

        when(repository.findDueRetryIds(DeliveryStatus.RETRY_SCHEDULED, now, batch))
                .thenReturn(List.of(first, second));

        scheduler.publishDueRetries();

        verify(repository).findDueRetryIds(DeliveryStatus.RETRY_SCHEDULED, now, batch);
        verify(publisher).publish(first);
        verify(publisher).publish(second);
        verifyNoMoreInteractions(publisher);
    }
}
