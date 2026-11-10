package com.wenghong.notifyflow.delivery.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class DeliveryJobPublisherTests {

    private static final UUID DELIVERY_ID = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final DeliveryJobPublisher publisher = new DeliveryJobPublisher(rabbitTemplate);

    @Test
    void publishSendsDeliveryIdAndAcceptsPositiveConfirmation() {
        onSend(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(true, null)));

        publisher.publish(DELIVERY_ID);

        verify(rabbitTemplate).convertAndSend(
                eq(DeliveryMessagingConfiguration.DELIVERY_EXCHANGE),
                eq(DeliveryMessagingConfiguration.DELIVERY_ROUTING_KEY),
                eq(DELIVERY_ID.toString()), any(CorrelationData.class));
        verifyNoMoreInteractions(rabbitTemplate);
    }

    @Test
    void repeatedPublicationsOfSameDeliveryHaveDifferentCorrelationIds() {
        onSend(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(true, null)));

        publisher.publish(DELIVERY_ID);
        publisher.publish(DELIVERY_ID);

        ArgumentCaptor<CorrelationData> correlations = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate, times(2)).convertAndSend(
                eq(DeliveryMessagingConfiguration.DELIVERY_EXCHANGE),
                eq(DeliveryMessagingConfiguration.DELIVERY_ROUTING_KEY),
                eq(DELIVERY_ID.toString()), correlations.capture());
        String firstId = correlations.getAllValues().getFirst().getId();
        String secondId = correlations.getAllValues().getLast().getId();
        assertNotNull(firstId);
        assertNotNull(secondId);
        assertTrue(!firstId.isBlank() && !secondId.isBlank());
        assertNotEquals(firstId, secondId);
    }

    @Test
    void negativeConfirmationFailsPublicationAndIncludesBrokerReason() {
        onSend(correlation -> correlation.getFuture().complete(
                new CorrelationData.Confirm(false, "Broker refused publication")));

        AmqpException error = assertThrows(AmqpException.class, () -> publisher.publish(DELIVERY_ID));

        assertTrue(error.getMessage().contains("Broker refused publication"));
    }

    @Test
    void returnedMessageFailsPublicationEvenWithPositiveConfirmation() {
        onSend(correlation -> {
            Message message = new Message(DELIVERY_ID.toString().getBytes(StandardCharsets.UTF_8),
                    new MessageProperties());
            correlation.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE",
                    DeliveryMessagingConfiguration.DELIVERY_EXCHANGE,
                    DeliveryMessagingConfiguration.DELIVERY_ROUTING_KEY));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        AmqpException error = assertThrows(AmqpException.class, () -> publisher.publish(DELIVERY_ID));

        assertTrue(error.getMessage().contains("could not be routed"));
    }

    @Test
    void confirmationFutureFailureIsPropagatedWithItsCause() {
        IllegalStateException failure = new IllegalStateException("Confirm channel closed");
        onSend(correlation -> correlation.getFuture().completeExceptionally(failure));

        AmqpException error = assertThrows(AmqpException.class, () -> publisher.publish(DELIVERY_ID));

        ExecutionException wrapped = assertInstanceOf(ExecutionException.class, error.getCause());
        assertSame(failure, wrapped.getCause());
    }

    @Test
    void sendFailurePropagatesWithoutWaitingForConfirmation() {
        AmqpException failure = new AmqpException("Connection unavailable");
        onSend(correlation -> { throw failure; });

        assertSame(failure, assertThrows(AmqpException.class, () -> publisher.publish(DELIVERY_ID)));
    }

    @Test
    void interruptedWaitFailsPublicationAndPreservesInterruptFlag() {
        onSend(correlation -> { });

        try {
            Thread.currentThread().interrupt();
            AmqpException error = assertThrows(AmqpException.class, () -> publisher.publish(DELIVERY_ID));
            assertInstanceOf(InterruptedException.class, error.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            // Avoid leaking this test's interruption into later tests on the same thread.
            Thread.interrupted();
        }
    }

    @Test
    void absentConfirmationFailsWithinBoundedWait() {
        onSend(correlation -> { });

        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            AmqpException error = assertThrows(AmqpException.class, () -> publisher.publish(DELIVERY_ID));
            assertInstanceOf(TimeoutException.class, error.getCause());
        });
    }

    private void onSend(Consumer<CorrelationData> action) {
        doAnswer(invocation -> {
            action.accept(invocation.getArgument(3));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(DeliveryMessagingConfiguration.DELIVERY_EXCHANGE),
                eq(DeliveryMessagingConfiguration.DELIVERY_ROUTING_KEY),
                eq(DELIVERY_ID.toString()), any(CorrelationData.class));
    }
}
