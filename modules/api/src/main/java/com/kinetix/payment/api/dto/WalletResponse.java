package com.kinetix.payment.api.dto;

import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.entity.DriverWallet;
import com.kinetix.payment.domain.entity.MerchantWallet;
import java.math.BigDecimal;

public record WalletResponse(
    Long id,
    String ownerPrincipalId,
    String ownerType,
    BigDecimal availableBalance,
    BigDecimal heldOrPendingBalance,
    String currency
) {
    public static WalletResponse fromCustomer(CustomerWallet wallet) {
        return new WalletResponse(
            wallet.id(),
            wallet.customerPrincipalId(),
            "CUSTOMER",
            wallet.balance(),
            wallet.heldBalance(),
            wallet.currency()
        );
    }

    public static WalletResponse fromMerchant(MerchantWallet wallet) {
        return new WalletResponse(
            wallet.id(),
            wallet.merchantPrincipalId(),
            "MERCHANT",
            wallet.availableBalance(),
            wallet.pendingEscrowBalance(),
            wallet.currency()
        );
    }

    public static WalletResponse fromDriver(DriverWallet wallet) {
        return new WalletResponse(
            wallet.id(),
            wallet.driverPrincipalId(),
            "DRIVER",
            wallet.availableBalance(),
            wallet.pendingEscrowBalance(),
            wallet.currency()
        );
    }
}
