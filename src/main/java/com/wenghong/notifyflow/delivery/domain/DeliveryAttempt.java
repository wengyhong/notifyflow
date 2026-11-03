package com.wenghong.notifyflow.delivery.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "delivery_attempts")
public class DeliveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_id", nullable = false)
    private Delivery delivery;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public UUID getId() {
        return id;
    }

    protected DeliveryAttempt() {
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public DeliveryAttempt(Delivery delivery, int attemptNumber, Instant startedAt) {

        if (attemptNumber < 1)
            throw new IllegalArgumentException("Attempt number must be positive.");
        this.delivery = delivery;
        this.attemptNumber = attemptNumber;
        this.startedAt = startedAt;
    }

    public void completeWithResponse(int responseStatus, Instant completedAt) {
        if (this.completedAt != null)
            throw new IllegalStateException("Attempt is already completed.");

        if (responseStatus < 100 || responseStatus > 599)
            throw new IllegalArgumentException("HTTP status must be between 100 and 599.");

        Objects.requireNonNull(completedAt);

        this.responseStatus = responseStatus;
        this.completedAt = completedAt;
    }

    public void completeWithFailure(String errorMessage, Instant completedAt) {
        if (this.completedAt != null)
            throw new IllegalStateException("Attempt is already completed.");
        Objects.requireNonNull(errorMessage);
        if (errorMessage.isBlank()) {
            throw new IllegalArgumentException("Error message must not be blank.");
        }
        Objects.requireNonNull(completedAt);

        this.errorMessage = errorMessage;
        this.completedAt = completedAt;

    }

}
