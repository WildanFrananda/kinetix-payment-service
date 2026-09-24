package com.kinetix.payment.infrastructure.grpc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class SpiffeTrustDomainTest {
    @Test
    void anUnsetVariableKeepsTheDomainTheEstateRunsToday() {
        assertEquals("spiffe://kinetix.local/service/", SpiffeId.TRUST_DOMAIN);
        assertEquals(java.util.List.of("spiffe://kinetix.local/service/"), SpiffeId.TRUST_PREFIXES);
    }

    @Test
    void aCutoverCanAcceptBothDomainsAtOnce() {
        var both = java.util.List.of(SpiffeId.prefixFor("kinetix.local"), SpiffeId.prefixFor("prod.kinetix"));

        for (String domain : java.util.List.of("kinetix.local", "prod.kinetix")) {
            String id = "spiffe://" + domain + "/service/order";
            var named = both.stream().map(p -> SpiffeId.serviceIn(id, p)).flatMap(Optional::stream).findFirst();
            assertEquals(Optional.of("order"), named);
        }
    }

    @Test
    void aDomainOutsideTheListIsStillRefused() {
        var both = java.util.List.of(SpiffeId.prefixFor("kinetix.local"), SpiffeId.prefixFor("prod.kinetix"));
        var named = both.stream()
                .map(p -> SpiffeId.serviceIn("spiffe://staging.kinetix/service/order", p))
                .flatMap(Optional::stream)
                .findFirst();
        assertTrue(named.isEmpty());
    }

    @Test
    void anIdFromTheConfiguredDomainNamesItsService() {
        assertEquals(
            Optional.of("order"),
            SpiffeId.serviceIn("spiffe://kinetix.local/service/order", SpiffeId.prefixFor("kinetix.local"))
        );
    }

    @Test
    void anotherDomainCanBeConfiguredWithoutTouchingThisCode() {
        assertEquals(
            Optional.of("order"),
            SpiffeId.serviceIn("spiffe://prod.kinetix/service/order", SpiffeId.prefixFor("prod.kinetix"))
        );
    }

    @Test
    void anIdFromAnotherTrustDomainNamesNobody() {
        assertTrue(
            SpiffeId.serviceIn("spiffe://prod.kinetix/service/order", SpiffeId.prefixFor("kinetix.local"))
                .isEmpty()
        );
        assertTrue(
            SpiffeId.serviceIn("spiffe://kinetix.local/service/order", SpiffeId.prefixFor("prod.kinetix"))
                .isEmpty()
        );
    }

    @Test
    void aDomainThisOneIsMerelyAPrefixOfIsRefused() {
        assertTrue(
            SpiffeId.serviceIn(
                "spiffe://kinetix.local.example.com/service/order",
                SpiffeId.prefixFor("kinetix.local"))
                    .isEmpty()
        );
    }

    @Test
    void anIdThatNamesNoServiceIsRefused() {
        String prefix = SpiffeId.prefixFor("kinetix.local");
        assertTrue(SpiffeId.serviceIn("spiffe://kinetix.local/service/", prefix).isEmpty());
        assertTrue(SpiffeId.serviceIn("spiffe://kinetix.local/service/a/b", prefix).isEmpty());
        assertTrue(SpiffeId.serviceIn(null, prefix).isEmpty());
    }
}
