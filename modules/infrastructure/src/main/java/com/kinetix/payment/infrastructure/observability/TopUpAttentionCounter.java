package com.kinetix.payment.infrastructure.observability;

import com.kinetix.payment.domain.entity.AttentionReason;
import com.kinetix.payment.domain.entity.PaymentTransaction;
import com.kinetix.payment.domain.port.TopUpAlarmPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TopUpAttentionCounter implements TopUpAlarmPort {
    static final String NAME = "kinetix.payment.top.ups.needing.attention";

    private static final String HELP =
        "Top-ups set aside as NEEDS_ATTENTION instead of being credited, by reason. Each one is money "
            + "the gateway holds that no wallet shows until a person resolves it.";

    private final Map<AttentionReason, Counter> counters = new EnumMap<>(AttentionReason.class);

    public TopUpAttentionCounter(MeterRegistry registry) {
        for (AttentionReason reason : AttentionReason.values()) {
            counters.put(
                reason,
                Counter.builder(NAME)
                    .description(HELP)
                    .tag("reason", reason.name())
                    .register(registry)
            );
        }
    }

    @Override
    public void setAside(PaymentTransaction transaction) {
        counters.get(transaction.attentionReason()).increment();
    }
}
