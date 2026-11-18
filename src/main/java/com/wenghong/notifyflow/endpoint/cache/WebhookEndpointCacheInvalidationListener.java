package com.wenghong.notifyflow.endpoint.cache;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.wenghong.notifyflow.endpoint.application.WebhookEndpointCache;
import com.wenghong.notifyflow.endpoint.application.WebhookEndpointChangedEvent;

@Component
public class WebhookEndpointCacheInvalidationListener {

        private final WebhookEndpointCache cache;

        public WebhookEndpointCacheInvalidationListener(WebhookEndpointCache cache) {
            this.cache = cache;
        }

        @TransactionalEventListener (phase = TransactionPhase.AFTER_COMMIT)
        public void handle(WebhookEndpointChangedEvent event)
        {
            cache.evict(event.endpointId());
        }
}
