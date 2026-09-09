package com.kinetix.payment.api.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;

class ShutdownStateTest {
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

    private Logger logger;

    @BeforeEach
    void captureTheShutdownLog() {
        logger = (Logger) LoggerFactory.getLogger(ShutdownState.class);
        captured.start();
        logger.addAppender(captured);
    }

    @AfterEach
    void releaseTheShutdownLog() {
        logger.detachAppender(captured);
        captured.stop();
    }

    @Test
    void theAnnouncementDoesNotClaimAnHttpRefusalThatDoesNotHappen() {
        String announcement = announceShutdown();

        assertFalse(
            announcement.contains("refusing new work"),
            "the log claims new work is refused from ContextClosedEvent. Measured under a real "
                + "SIGTERM: a brand-new connection to a business route was accepted and "
                + "dispatched at every one of nine one-second polls afterwards. Nothing checks "
                + "this claim and the process does not hold it: " + announcement
        );
        assertTrue(
            announcement.contains("readiness"),
            "the only thing that changes at ContextClosedEvent is readiness, and the line should "
                + "say so: " + announcement
        );
        assertTrue(
            announcement.contains("connector"),
            "the line should say what really stops accepting HTTP, and when: " + announcement
        );
    }

    @Test
    void readinessTurnsToDrainingAndSaysSoExactlyOnce() {
        ShutdownState state = new ShutdownState();
        assertFalse(state.isDraining());

        ContextClosedEvent closed = new ContextClosedEvent(new GenericApplicationContext());
        state.onContextClosed(closed);
        state.onContextClosed(closed);

        assertTrue(state.isDraining());
        assertEquals(1, captured.list.size(), messagesOf(captured.list).toString());
    }

    private String announceShutdown() {
        new ShutdownState().onContextClosed(new ContextClosedEvent(new GenericApplicationContext()));

        assertEquals(1, captured.list.size(), messagesOf(captured.list).toString());
        return captured.list.get(0).getFormattedMessage();
    }

    private static List<String> messagesOf(List<ILoggingEvent> events) {
        return events.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
