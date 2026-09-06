package com.kinetix.payment.api.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccessClaimsTest {

    private static final String ALICE = "9f1d4a3e-1c62-4d0a-9a7b-2f5c8e0b41d7";
    private static final String BOB = "3c9a77b1-58de-4a01-8f2e-6d4b19c0a8f3";

    private static AccessClaims customer(String principalId) {
        return new AccessClaims(principalId, "someone@kinetix.test", AccessClaims.CUSTOMER);
    }

    @Test
    @DisplayName("a customer reaches their own wallet")
    void ownWalletIsReachable() {
        assertTrue(customer(ALICE).mayActOn(AccessClaims.CUSTOMER, ALICE));
    }

    @Test
    @DisplayName("a customer does not reach anybody else's wallet")
    void anotherPrincipalIsRefused() {
        assertFalse(customer(ALICE).mayActOn(AccessClaims.CUSTOMER, BOB));
    }

    @Test
    @DisplayName("holding the right principal for the wrong kind of wallet is still refused")
    void roleMustMatchTheWallet() {
        assertFalse(customer(ALICE).mayActOn(AccessClaims.SELLER, ALICE));
        assertFalse(customer(ALICE).mayActOn(AccessClaims.COURIER, ALICE));
    }

    @Test
    @DisplayName("a token with no principal reaches nothing")
    void aMissingPrincipalMatchesNothing() {
        assertFalse(customer(null).mayActOn(AccessClaims.CUSTOMER, ALICE));
        assertFalse(customer(null).mayActOn(AccessClaims.CUSTOMER, null));
    }

    @Test
    @DisplayName("an admin reaches any wallet, and that is deliberate")
    void adminIsUnrestricted() {
        AccessClaims admin = new AccessClaims(ALICE, "ops@kinetix.test", AccessClaims.ADMIN);
        assertTrue(admin.mayActOn(AccessClaims.CUSTOMER, BOB));
        assertTrue(admin.mayActOn(AccessClaims.SELLER, BOB));
    }
}
