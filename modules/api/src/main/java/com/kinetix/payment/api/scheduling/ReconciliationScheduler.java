package com.kinetix.payment.api.scheduling;

import com.kinetix.payment.api.lifecycle.ShutdownState;
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
    private final ShutdownState shutdownState;
    private final Duration pendingTopUpAge;
    private final int topUpBatchSize;
    private final Duration topUpInterval;
    private final Duration notFoundGrace;

    public ReconciliationScheduler(
        TopUpService topUps,
        ShutdownState shutdownState,
        @Value("${kinetix.reconciliation.pending-top-up-age}") Duration pendingTopUpAge,
        @Value("${kinetix.reconciliation.top-up-batch-size}") int topUpBatchSize,
        @Value("${kinetix.reconciliation.top-up-interval}") Duration topUpInterval,
        @Value("${kinetix.reconciliation.not-found-grace}") Duration notFoundGrace
    ) {
        this.topUps = topUps;
        this.shutdownState = shutdownState;
        this.pendingTopUpAge = pendingTopUpAge;
        this.topUpBatchSize = topUpBatchSize;
        this.topUpInterval = topUpInterval;
        this.notFoundGrace = notFoundGrace;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void announce() {
        LOG.info(
            "reconciliation armed: top-ups older than {} every {} (max {} per sweep); a charge the gateway "
                + "still does not know after {} is concluded",
            pendingTopUpAge,
            topUpInterval,
            topUpBatchSize,
            notFoundGrace
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
            int concluded = topUps.reconcilePendingTopUps(pendingTopUpAge, topUpBatchSize, notFoundGrace);
            if (concluded > 0) {
                LOG.info("top-up reconciliation concluded {} top-up(s)", concluded);
            }
        } catch (RuntimeException failure) {
            LOG.error("top-up reconciliation failed; the next run will try again", failure);
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
