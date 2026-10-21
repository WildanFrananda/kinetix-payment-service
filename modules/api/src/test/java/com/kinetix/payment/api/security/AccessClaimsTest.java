package com.kinetix.payment.api.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccessClaimsTest {
    private static final String ALICE = "9f1d4a3e-1c62-4d0a-9a7b-2f5c8e0b41d7";

    private static AccessClaims customer(String principalId) {
        return new AccessClaims(principalId, "someone@kinetix.test", AccessClaims.CUSTOMER);
    }

    @Test
    @DisplayName("a customer holds a customer wallet")
    void aCustomerHoldsACustomerWallet() {
        assertTrue(customer(ALICE).holds(AccessClaims.CUSTOMER));
    }

    @Test
    @DisplayName("holding a principal for the wrong kind of wallet is still refused")
    void roleMustMatchTheWallet() {
        assertFalse(customer(ALICE).holds(AccessClaims.SELLER));
        assertFalse(customer(ALICE).holds(AccessClaims.COURIER));
    }

    @Test
    @DisplayName("a token with no principal holds nothing")
    void aMissingPrincipalHoldsNothing() {
        assertFalse(customer(null).holds(AccessClaims.CUSTOMER));
    }

    @Test
    @DisplayName("an admin holds no wallet, so an admin can neither open one nor top one up under its own principal")
    void anAdminHoldsNoWallet() {
        AccessClaims admin = new AccessClaims(ALICE, "ops@kinetix.test", AccessClaims.ADMIN);

        assertFalse(admin.holds(AccessClaims.CUSTOMER));
        assertFalse(admin.holds(AccessClaims.SELLER));
        assertFalse(admin.holds(AccessClaims.COURIER));
    }
}
