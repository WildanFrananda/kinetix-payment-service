package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.EscrowHold;

public record EscrowOutcome(EscrowHold hold, boolean alreadyApplied) {}
