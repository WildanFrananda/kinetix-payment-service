package com.kinetix.payment.infrastructure.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CallerAllowListTest {
    private static final String ESCROW = "payment.v1.PaymentService";
    private static final String LIABILITIES = "payment.v1.LiabilityService";
    private static final String REFLECTION = "grpc.reflection.v1.ServerReflection";

    @Test
    void aBareCallerReachesEveryServiceOnTheServer() {
        CallerAllowList allowed = CallerAllowList.parse("order, notary@" + LIABILITIES);

        assertTrue(allowed.permits("order", ESCROW));
        assertTrue(allowed.permits("order", REFLECTION));
    }

    @Test
    void aScopedCallerReachesItsServiceAndNothingElse() {
        CallerAllowList allowed = CallerAllowList.parse("order,notary@" + LIABILITIES);

        assertTrue(allowed.permits("notary", LIABILITIES));
        assertFalse(allowed.permits("notary", ESCROW));
        assertFalse(allowed.permits("notary", REFLECTION));
    }

    @Test
    void aCallerCanBeScopedToSeveralServices() {
        CallerAllowList allowed = CallerAllowList.parse("notary@" + LIABILITIES + ",notary@" + REFLECTION);

        assertTrue(allowed.permits("notary", LIABILITIES));
        assertTrue(allowed.permits("notary", REFLECTION));
        assertFalse(allowed.permits("notary", ESCROW));
    }

    @Test
    void aBareEntryWinsOverAScopedOneForTheSameCaller() {
        CallerAllowList allowed = CallerAllowList.parse("order@" + LIABILITIES + ",order");

        assertTrue(allowed.permits("order", ESCROW));
        assertEquals("order (any service)", allowed.describe());
    }

    @Test
    void anUnlistedCallerReachesNothing() {
        CallerAllowList allowed = CallerAllowList.parse("order,notary@" + LIABILITIES);

        assertFalse(allowed.permits("catalog", ESCROW));
        assertFalse(allowed.permits("catalog", LIABILITIES));
        assertFalse(allowed.permits("notary@" + LIABILITIES, LIABILITIES));
    }

    @Test
    void aMalformedEntryStopsTheServerFromStarting() {
        for (String raw : new String[] {"order@", "@" + LIABILITIES, "notary@a@b", "order, @x"}) {
            assertThrows(IllegalStateException.class, () -> CallerAllowList.parse(raw), raw);
        }
    }

    @Test
    void anEmptyListIsEmpty() {
        assertTrue(CallerAllowList.parse("").isEmpty());
        assertTrue(CallerAllowList.parse(" , ,").isEmpty());
        assertTrue(CallerAllowList.parse(null).isEmpty());
    }

    @Test
    void theStartupLineNamesEveryCallerAndItsReach() {
        CallerAllowList allowed = CallerAllowList.parse("order,notary@" + LIABILITIES);

        assertEquals("notary (" + LIABILITIES + "), order (any service)", allowed.describe());
    }
}
