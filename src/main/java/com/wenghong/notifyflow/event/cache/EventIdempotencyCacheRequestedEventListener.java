package com.wenghong.notifyflow.event.cache;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.wenghong.notifyflow.event.application.EventIdempotencyCache;
import com.wenghong.notifyflow.event.application.EventIdempotencyCacheRequestedEvent;

@Component
public class EventIdempotencyCacheRequestedEventListener {

    private final EventIdempotencyCache cache;

    public EventIdempotencyCacheRequestedEventListener(EventIdempotencyCache cache) {
        this.cache = cache;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handle(EventIdempotencyCacheRequestedEvent event) {
        cache.put(event.idempotencyKey(), event.snapshot());
    }
}
