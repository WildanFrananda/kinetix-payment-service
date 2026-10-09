package com.kinetix.payment.domain.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EscrowMovementTest {

    private static final String MERCHANT = "3c9a77b1-58de-4a01-8f2e-6d4b19c0a8f3";
    private static final String DRIVER = "b7e2c05f-9a34-4c88-b1d6-0e7a3f52d914";

    private static EscrowHold held() {
        return new EscrowHold(
            42L, "ORD-1", "9f1d4a3e-1c62-4d0a-9a7b-2f5c8e0b41d7", MERCHANT, DRIVER,
            new BigDecimal("150000.00"), new BigDecimal("130000.00"), new BigDecimal("20000.00"),
            EscrowHold.EscrowStatus.HELD, Instant.now(), null, null, BigDecimal.ZERO
        );
    }

    @Test
    void releasingMoreThanAMerchantHoldsPendingIsRefusedRatherThanCreatingMoney() {
        MerchantWallet wallet = MerchantWallet.createInitial(MERCHANT).addPendingEscrow(new BigDecimal("100.00"));

        assertThrows(IllegalStateException.class, () -> wallet.releaseEscrowToAvailable(new BigDecimal("100.01")));
    }

    @Test
    void aNegativeEscrowMovementIsRefusedOnEveryWallet() {
        BigDecimal negative = new BigDecimal("-1.00");

        assertThrows(IllegalArgumentException.class, () -> MerchantWallet.createInitial(MERCHANT).addPendingEscrow(negative));
        assertThrows(IllegalArgumentException.class, () -> DriverWallet.createInitial(DRIVER).addPendingEscrow(negative));
        assertThrows(IllegalArgumentException.class,
            () -> SuspenseWallet.createInitial(SuspenseWallet.UNASSIGNED_DRIVER_SHIPPING_FEE).addPendingEscrow(negative)
        );
    }

    @Test
    void cancellingMoreThanADriverHoldsPendingIsRefused() {
        DriverWallet wallet = DriverWallet.createInitial(DRIVER).addPendingEscrow(new BigDecimal("50.00"));

        assertThrows(IllegalStateException.class, () -> wallet.cancelPendingEscrow(new BigDecimal("60.00")));
    }

    @Test
    void aDriverPaidAFeeTheyNeverHeldPendingIsCreditedDirectly() {
        DriverWallet paid = DriverWallet.createInitial(DRIVER).creditAvailable(new BigDecimal("20000.00"));

        assertEquals(0, new BigDecimal("20000.00").compareTo(paid.availableBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(paid.pendingEscrowBalance()));
    }

    @Test
    void goodsAreRefundedOutOfTheMerchantsShareAndNoFurther() {
        EscrowHold once = held().refundGoods(new BigDecimal("30000.00"));

        assertEquals(0, new BigDecimal("100000.00").compareTo(once.merchantAmountOutstanding()));
        assertThrows(IllegalArgumentException.class, () -> once.refundGoods(new BigDecimal("100000.01")));
    }

    @Test
    void goodsAreNotRefundedFromAHoldThatIsNoLongerHeld() {
        EscrowHold released = held().markAsReleased();

        assertThrows(IllegalStateException.class, () -> released.refundGoods(new BigDecimal("1.00")));
    }

    @Test
    void aGoodsRefundMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> held().refundGoods(BigDecimal.ZERO));
    }
}
