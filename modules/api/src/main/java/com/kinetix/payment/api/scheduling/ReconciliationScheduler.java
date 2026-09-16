package com.kinetix.payment.api.scheduling;

import com.kinetix.payment.api.lifecycle.ShutdownState;
import com.kinetix.payment.application.EscrowService;
import com.kinetix.payment.application.TopUpService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!migrate")
public class ReconciliationScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final TopUpService topUps;
    private final EscrowService escrow;
    private final ShutdownState shutdownState;
    private final Duration pendingTopUpAge;
    private final int topUpBatchSize;
    private final Duration topUpInterval;
    private final Duration escrowInterval;

    public ReconciliationScheduler(
        TopUpService topUps,
        EscrowService escrow,
        ShutdownState shutdownState,
        @Value("${kinetix.reconciliation.pending-top-up-age}") Duration pendingTopUpAge,
        @Value("${kinetix.reconciliation.top-up-batch-size}") int topUpBatchSize,
        @Value("${kinetix.reconciliation.top-up-interval}") Duration topUpInterval,
        @Value("${kinetix.reconciliation.escrow-interval}") Duration escrowInterval
    ) {
        this.topUps = topUps;
        this.escrow = escrow;
        this.shutdownState = shutdownState;
        this.pendingTopUpAge = pendingTopUpAge;
        this.topUpBatchSize = topUpBatchSize;
        this.topUpInterval = topUpInterval;
        this.escrowInterval = escrowInterval;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void announce() {
        LOG.info("reconciliation armed: top-ups older than {} every {} (max {} per sweep), "
            + "escrow auto-release every {}",
            pendingTopUpAge, topUpInterval, topUpBatchSize, escrowInterval
        );
    }

    @Scheduled(
        initialDelayString = "${kinetix.reconciliation.top-up-initial-delay}",
        fixedDelayString = "${kinetix.reconciliation.top-up-interval}"
    )
    public void reconcilePendingTopUps() {
        if (draining("top-up reconciliation")) {
            return;
        }
        try {
            int concluded = topUps.reconcilePendingTopUps(pendingTopUpAge, topUpBatchSize);
            if (concluded > 0) {
                LOG.info("top-up reconciliation concluded {} top-up(s)", concluded);
            }
        } catch (RuntimeException failure) {
            LOG.error("top-up reconciliation failed; the next run will try again", failure);
        }
    }

    @Scheduled(
        initialDelayString = "${kinetix.reconciliation.escrow-initial-delay}",
        fixedDelayString = "${kinetix.reconciliation.escrow-interval}"
    )
    public void releaseDueEscrow() {
        if (draining("escrow auto-release")) {
            return;
        }
        try {
            int released = escrow.processAutoReleaseJob();
            if (released > 0) {
                LOG.info("escrow auto-release released {} hold(s)", released);
            }
        } catch (RuntimeException failure) {
            LOG.error("escrow auto-release failed; the next run will try again", failure);
        }
    }

    private boolean draining(String sweep) {
        if (shutdownState.isDraining()) {
            LOG.info("{} skipped: this instance is shutting down", sweep);
            return true;
        }
        return false;
    }
}
