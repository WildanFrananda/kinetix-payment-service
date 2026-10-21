package com.kinetix.payment.api.dto;

import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.entity.DriverWallet;
import com.kinetix.payment.domain.entity.MerchantWallet;
import java.math.BigDecimal;
import java.math.RoundingMode;

public record WalletResponse(
    Long id,
    String ownerPrincipalId,
    String ownerType,
    boolean exists,
    BigDecimal availableBalance,
    BigDecimal heldOrPendingBalance,
    String currency
) {
    private static final int LEDGER_SCALE = 2;

    public static WalletResponse fromCustomer(CustomerWallet wallet) {
        return new WalletResponse(
            wallet.id(),
            wallet.customerPrincipalId(),
            "CUSTOMER",
            wallet.id() != null,
            money(wallet.balance()),
            money(wallet.heldBalance()),
            wallet.currency()
        );
    }

    public static WalletResponse fromMerchant(MerchantWallet wallet) {
        return new WalletResponse(
            wallet.id(),
            wallet.merchantPrincipalId(),
            "MERCHANT",
            wallet.id() != null,
            money(wallet.availableBalance()),
            money(wallet.pendingEscrowBalance()),
            wallet.currency()
        );
    }

    public static WalletResponse fromDriver(DriverWallet wallet) {
        return new WalletResponse(
            wallet.id(),
            wallet.driverPrincipalId(),
            "DRIVER",
            wallet.id() != null,
            money(wallet.availableBalance()),
            money(wallet.pendingEscrowBalance()),
            wallet.currency()
        );
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(LEDGER_SCALE, RoundingMode.UNNECESSARY);
    }
}
