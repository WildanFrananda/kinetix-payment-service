package com.kinetix.payment.domain.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WalletPrincipalTest {

    private static final String PRINCIPAL = "9f1d4a3e-1c62-4d0a-9a7b-2f5c8e0b41d7";

    @Test
    @DisplayName("a customer wallet refuses to exist without a principal")
    void customerWalletRequiresAPrincipal() {
        assertThrows(IllegalArgumentException.class, () -> CustomerWallet.createInitial(null));
        assertThrows(IllegalArgumentException.class, () -> CustomerWallet.createInitial(""));
        assertThrows(IllegalArgumentException.class, () -> CustomerWallet.createInitial("   "));
    }

    @Test
    @DisplayName("a merchant wallet refuses to exist without a principal")
    void merchantWalletRequiresAPrincipal() {
        assertThrows(IllegalArgumentException.class, () -> MerchantWallet.createInitial(null));
        assertThrows(IllegalArgumentException.class, () -> MerchantWallet.createInitial("  "));
    }

    @Test
    @DisplayName("a driver wallet refuses to exist without a principal")
    void driverWalletRequiresAPrincipal() {
        assertThrows(IllegalArgumentException.class, () -> DriverWallet.createInitial(null));
        assertThrows(IllegalArgumentException.class, () -> DriverWallet.createInitial("  "));
    }

    @Test
    @DisplayName("a principal survives the operations that rebuild the record")
    void principalSurvivesTopUpAndDeduction() {
        CustomerWallet funded = CustomerWallet.createInitial(PRINCIPAL).topUp(new BigDecimal("500.00"));
        assertEquals(PRINCIPAL, funded.customerPrincipalId());

        CustomerWallet spent = funded.deductForCheckout(new BigDecimal("200.00"));
        assertEquals(PRINCIPAL, spent.customerPrincipalId());
        assertEquals(new BigDecimal("300.00"), spent.balance());
    }

    @Test
    @DisplayName("an escrow hold needs a customer and a merchant, but not yet a driver")
    void escrowRequiresBothSidesButNotACourier() {
        String merchant = "3c9a77b1-58de-4a01-8f2e-6d4b19c0a8f3";

        EscrowHold unassigned = EscrowHold.createNewHold(
            "ORD-2001", PRINCIPAL, merchant, null,
            new BigDecimal("150000.00"), new BigDecimal("130000.00"), new BigDecimal("20000.00"));
        assertEquals(PRINCIPAL, unassigned.customerPrincipalId());
        assertEquals(merchant, unassigned.merchantPrincipalId());

        assertThrows(IllegalArgumentException.class, () -> EscrowHold.createNewHold(
            "ORD-2002", "", merchant, null,
            new BigDecimal("1.00"), new BigDecimal("1.00"), BigDecimal.ZERO));

        assertThrows(IllegalArgumentException.class, () -> EscrowHold.createNewHold(
            "ORD-2003", PRINCIPAL, null, null,
            new BigDecimal("1.00"), new BigDecimal("1.00"), BigDecimal.ZERO));
    }

    @Test
    @DisplayName("releasing a hold keeps every principal on it")
    void releaseKeepsPrincipals() {
        String merchant = "3c9a77b1-58de-4a01-8f2e-6d4b19c0a8f3";
        String driver = "b7e2c05f-9a34-4c88-b1d6-0e7a3f52d914";

        EscrowHold released = EscrowHold.createNewHold(
            "ORD-2004", PRINCIPAL, merchant, driver,
            new BigDecimal("150000.00"), new BigDecimal("130000.00"), new BigDecimal("20000.00"))
            .markAsReleased();

        assertEquals(PRINCIPAL, released.customerPrincipalId());
        assertEquals(merchant, released.merchantPrincipalId());
        assertEquals(driver, released.driverPrincipalId());
        assertEquals(EscrowHold.EscrowStatus.RELEASED, released.status());
    }
}
