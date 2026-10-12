package com.kinetix.payment.domain.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LiabilityHolderTest {
    private static final String PRINCIPAL = "9f1d4a3e-1c62-4d0a-9a7b-2f5c8e0b41d7";

    @Test
    @DisplayName("a customer is owed their balance, and a zero balance is still owed")
    void customerIsOwedTheBalance() {
        List<LiabilityAccount> funded = CustomerWallet.createInitial(PRINCIPAL)
            .topUp(new BigDecimal("125000.50"))
            .liabilities();
        List<LiabilityAccount> empty = CustomerWallet.createInitial(PRINCIPAL).liabilities();

        assertEquals(List.of(account(LiabilityKind.CUSTOMER_BALANCE, PRINCIPAL, "125000.50")), funded);
        assertEquals(List.of(account(LiabilityKind.CUSTOMER_BALANCE, PRINCIPAL, "0")), empty);
    }

    @Test
    @DisplayName("a held balance stops the snapshot instead of being left out of it")
    void heldBalanceIsRefused() {
        CustomerWallet held = new CustomerWallet(
            1L, PRINCIPAL, BigDecimal.ONE, new BigDecimal("0.01"), "IDR", Instant.now(), Instant.now()
        );

        IllegalStateException refused = assertThrows(IllegalStateException.class, held::liabilities);

        assertTrue(refused.getMessage().contains("held_balance"));
    }

    @Test
    @DisplayName("a merchant is owed what is available and what escrow still holds for them, separately")
    void merchantIsOwedBothBalances() {
        MerchantWallet wallet = MerchantWallet.createInitial(PRINCIPAL)
            .addPendingEscrow(new BigDecimal("130000.00"))
            .releaseEscrowToAvailable(new BigDecimal("30000.00"));

        assertEquals(List.of(
            account(LiabilityKind.MERCHANT_AVAILABLE, PRINCIPAL, "30000.00"),
            account(LiabilityKind.MERCHANT_PENDING_ESCROW, PRINCIPAL, "100000.00")
        ), wallet.liabilities());
    }

    @Test
    @DisplayName("a driver is owed what is available and what escrow still holds for them, separately")
    void driverIsOwedBothBalances() {
        DriverWallet wallet = DriverWallet.createInitial(PRINCIPAL)
            .addPendingEscrow(new BigDecimal("20000.00"))
            .creditAvailable(new BigDecimal("5000.00"));

        assertEquals(List.of(
            account(LiabilityKind.DRIVER_AVAILABLE, PRINCIPAL, "5000.00"),
            account(LiabilityKind.DRIVER_PENDING_ESCROW, PRINCIPAL, "20000.00")
        ), wallet.liabilities());
    }

    @Test
    @DisplayName("suspense money is owed under its purpose, not under a principal")
    void suspenseIsOwedUnderItsPurpose() {
        List<LiabilityAccount> accounts = SuspenseWallet
            .createInitial(SuspenseWallet.UNASSIGNED_DRIVER_SHIPPING_FEE)
            .addPendingEscrow(new BigDecimal("20000.00"))
            .liabilities();

        assertEquals(List.of(
            account(LiabilityKind.SUSPENSE_AVAILABLE, SuspenseWallet.UNASSIGNED_DRIVER_SHIPPING_FEE, "0"),
            account(LiabilityKind.SUSPENSE_PENDING_ESCROW, SuspenseWallet.UNASSIGNED_DRIVER_SHIPPING_FEE, "20000.00")
        ), accounts);
        assertTrue(accounts.stream().noneMatch(LiabilityAccount::ownedByPrincipal));
    }

    @Test
    @DisplayName("every wallet kind owed to a person is owed to a principal")
    void principalKindsAreOwnedByPrincipals() {
        for (LiabilityKind kind : LiabilityKind.values()) {
            boolean suspense = kind.name().startsWith("SUSPENSE_");
            assertEquals(!suspense, account(kind, PRINCIPAL, "1").ownedByPrincipal(), kind.name());
        }
    }

    @Test
    @DisplayName("a liability is never negative, never anonymous and never without a kind")
    void malformedLiabilitiesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> account(LiabilityKind.DRIVER_AVAILABLE, PRINCIPAL, "-0.01"));
        assertThrows(IllegalArgumentException.class, () -> account(LiabilityKind.DRIVER_AVAILABLE, " ", "1"));
        assertThrows(IllegalArgumentException.class, () -> account(null, PRINCIPAL, "1"));
        assertThrows(IllegalArgumentException.class,
            () -> new LiabilityAccount(LiabilityKind.DRIVER_AVAILABLE, PRINCIPAL, BigDecimal.ONE, "")
        );
        assertFalse(account(LiabilityKind.SUSPENSE_AVAILABLE, "x", "0").ownedByPrincipal());
    }

    private static LiabilityAccount account(LiabilityKind kind, String holder, String amount) {
        return new LiabilityAccount(kind, holder, new BigDecimal(amount), "IDR");
    }
}
