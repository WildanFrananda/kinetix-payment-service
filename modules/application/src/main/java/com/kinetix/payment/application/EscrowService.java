package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.entity.DriverWallet;
import com.kinetix.payment.domain.entity.EscrowHold;
import com.kinetix.payment.domain.entity.MerchantWallet;
import com.kinetix.payment.domain.exception.EscrowNotFoundException;
import com.kinetix.payment.domain.port.CustomerWalletRepositoryPort;
import com.kinetix.payment.domain.port.DriverWalletRepositoryPort;
import com.kinetix.payment.domain.port.EscrowRepositoryPort;
import com.kinetix.payment.domain.port.MerchantWalletRepositoryPort;
import java.math.BigDecimal;
import java.util.List;

public class EscrowService {
    private final EscrowRepositoryPort escrowRepository;
    private final CustomerWalletRepositoryPort customerWalletRepository;
    private final MerchantWalletRepositoryPort merchantWalletRepository;
    private final DriverWalletRepositoryPort driverWalletRepository;

    public EscrowService(
        EscrowRepositoryPort escrowRepository,
        CustomerWalletRepositoryPort customerWalletRepository,
        MerchantWalletRepositoryPort merchantWalletRepository,
        DriverWalletRepositoryPort driverWalletRepository
    ) {
        this.escrowRepository = escrowRepository;
        this.customerWalletRepository = customerWalletRepository;
        this.merchantWalletRepository = merchantWalletRepository;
        this.driverWalletRepository = driverWalletRepository;
    }

    public EscrowHold createEscrowHold(
        String orderNumber,
        String customerPrincipalId,
        String merchantPrincipalId,
        String driverPrincipalId,
        BigDecimal totalOrderAmount,
        BigDecimal merchantAmount,
        BigDecimal shippingFeeAmount
    ) {
        CustomerWallet wallet = customerWalletRepository.findByCustomerPrincipalId(customerPrincipalId)
            .orElseGet(() -> CustomerWallet.createInitial(customerPrincipalId));

        CustomerWallet updatedWallet = wallet.deductForCheckout(totalOrderAmount);
        customerWalletRepository.save(updatedWallet);

        MerchantWallet merchantWallet = merchantWalletRepository.findByMerchantPrincipalId(merchantPrincipalId)
            .orElseGet(() -> MerchantWallet.createInitial(merchantPrincipalId));
        merchantWalletRepository.save(merchantWallet.addPendingEscrow(merchantAmount));

        if (driverPrincipalId != null && !driverPrincipalId.isBlank()) {
            DriverWallet driverWallet = driverWalletRepository.findByDriverPrincipalId(driverPrincipalId)
                .orElseGet(() -> DriverWallet.createInitial(driverPrincipalId));
            driverWalletRepository.save(driverWallet.addPendingEscrow(shippingFeeAmount));
        }

        EscrowHold hold = EscrowHold.createNewHold(
            orderNumber,
            customerPrincipalId,
            merchantPrincipalId,
            driverPrincipalId,
            totalOrderAmount,
            merchantAmount,
            shippingFeeAmount
        );
        return escrowRepository.save(hold);
    }

    public EscrowHold findByOrderNumber(String orderNumber) {
        return escrowRepository.findByOrderNumber(orderNumber).orElse(null);
    }

    public EscrowHold releaseEscrow(String orderNumber) {
        EscrowHold hold = escrowRepository.findByOrderNumber(orderNumber)
            .orElseThrow(() -> new EscrowNotFoundException("Escrow hold not found for order: " + orderNumber));

        if (hold.status() == EscrowHold.EscrowStatus.RELEASED) {
            return hold;
        }

        MerchantWallet merchantWallet = merchantWalletRepository.findByMerchantPrincipalId(hold.merchantPrincipalId())
            .orElseGet(() -> MerchantWallet.createInitial(hold.merchantPrincipalId()));
        merchantWalletRepository.save(merchantWallet.releaseEscrowToAvailable(hold.merchantAmount()));

        if (hold.driverPrincipalId() != null && !hold.driverPrincipalId().isBlank()) {
            DriverWallet driverWallet = driverWalletRepository.findByDriverPrincipalId(hold.driverPrincipalId())
                .orElseGet(() -> DriverWallet.createInitial(hold.driverPrincipalId()));
            driverWalletRepository.save(driverWallet.releaseEscrowToAvailable(hold.shippingFeeAmount()));
        }

        EscrowHold released = hold.markAsReleased();
        return escrowRepository.save(released);
    }

    public EscrowHold refundEscrow(String orderNumber) {
        EscrowHold hold = escrowRepository.findByOrderNumber(orderNumber).orElse(null);
        if (hold == null) {
            return null;
        }

        if (hold.status() == EscrowHold.EscrowStatus.REFUNDED) {
            return hold;
        }

        if (hold.status() == EscrowHold.EscrowStatus.RELEASED) {
            throw new IllegalStateException(
                "escrow for order " + orderNumber + " was already released and cannot be refunded here");
        }

        CustomerWallet customerWallet =
            customerWalletRepository.findByCustomerPrincipalId(hold.customerPrincipalId())
                .orElseGet(() -> CustomerWallet.createInitial(hold.customerPrincipalId()));
        customerWalletRepository.save(customerWallet.topUp(hold.totalOrderAmount()));

        MerchantWallet merchantWallet =
            merchantWalletRepository.findByMerchantPrincipalId(hold.merchantPrincipalId())
                .orElseGet(() -> MerchantWallet.createInitial(hold.merchantPrincipalId()));
        merchantWalletRepository.save(merchantWallet.cancelPendingEscrow(hold.merchantAmount()));

        if (hold.driverPrincipalId() != null && !hold.driverPrincipalId().isBlank()) {
            DriverWallet driverWallet =
                driverWalletRepository.findByDriverPrincipalId(hold.driverPrincipalId())
                    .orElseGet(() -> DriverWallet.createInitial(hold.driverPrincipalId()));
            driverWalletRepository.save(driverWallet.cancelPendingEscrow(hold.shippingFeeAmount()));
        }

        return escrowRepository.save(hold.markAsRefunded());
    }

    public void processAutoReleaseJob() {
        List<EscrowHold> pendingHolds = escrowRepository.findPendingAutoReleaseHolds();
        for (EscrowHold hold : pendingHolds) {
            releaseEscrow(hold.orderNumber());
        }
    }
}
