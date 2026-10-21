package com.kinetix.payment.infrastructure.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.kinetix.payment.domain.entity.AttentionReason;
import com.kinetix.payment.domain.entity.PaymentTransaction;
import com.kinetix.payment.domain.entity.PaymentTransaction.PaymentMethod;
import com.kinetix.payment.domain.gateway.VirtualAccountBank;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TopUpAttentionCounterTest {
    @Test
    void everyReasonIsExposedAtZeroBeforeAnythingIsSetAside() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new TopUpAttentionCounter(registry);

        for (AttentionReason reason : AttentionReason.values()) {
            assertEquals(0.0, count(registry, reason), reason.name());
        }
    }

    @Test
    void aTopUpSetAsideCountsOnceUnderItsReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TopUpAttentionCounter counter = new TopUpAttentionCounter(registry);

        counter.setAside(
            PaymentTransaction.pendingTopUp(
                "TOPUP-1",
                "customer",
                "k",
                PaymentMethod.MIDTRANS_VA,
                VirtualAccountBank.BCA,
                new BigDecimal("50000")
            )
                .setAsideFor(AttentionReason.BALANCE_LIMIT_EXCEEDED, "mid-1", "{}")
        );

        assertEquals(1.0, count(registry, AttentionReason.BALANCE_LIMIT_EXCEEDED));
        assertEquals(0.0, count(registry, AttentionReason.AMOUNT_MISMATCH));
    }

    private static double count(SimpleMeterRegistry registry, AttentionReason reason) {
        return registry.get(TopUpAttentionCounter.NAME).tag("reason", reason.name()).counter().count();
    }
}
