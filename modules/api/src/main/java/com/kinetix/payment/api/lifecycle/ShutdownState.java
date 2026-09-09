package com.kinetix.payment.api.lifecycle;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ShutdownState {
    private static final Logger LOG = LoggerFactory.getLogger(ShutdownState.class);

    private final AtomicBoolean draining = new AtomicBoolean(false);

    public boolean isDraining() {
        return draining.get();
    }

    @EventListener
    public void onContextClosed(ContextClosedEvent closed) {
        if (draining.compareAndSet(false, true)) {
            LOG.info("shutting down: refusing new work, draining what is already in flight");
        }
    }
}
