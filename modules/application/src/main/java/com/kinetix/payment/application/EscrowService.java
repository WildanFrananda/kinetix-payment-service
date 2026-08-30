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
        Long customerId,
        Long merchantId,
        Long driverId,
        BigDecimal totalOrderAmount,
        BigDecimal merchantAmount,
        BigDecimal shippingFeeAmount
    ) {
        CustomerWallet wallet = customerWalletRepository.findByCustomerId(customerId)
            .orElseGet(() -> CustomerWallet.createInitial(customerId));

        CustomerWallet updatedWallet = wallet.deductForCheckout(totalOrderAmount);
        customerWalletRepository.save(updatedWallet);

        MerchantWallet merchantWallet = merchantWalletRepository.findByMerchantId(merchantId)
            .orElseGet(() -> MerchantWallet.createInitial(merchantId));
        merchantWalletRepository.save(merchantWallet.addPendingEscrow(merchantAmount));

        if (driverId != null && driverId > 0) {
            DriverWallet driverWallet = driverWalletRepository.findByDriverId(driverId)
                .orElseGet(() -> DriverWallet.createInitial(driverId));
            driverWalletRepository.save(driverWallet.addPendingEscrow(shippingFeeAmount));
        }

        EscrowHold hold = EscrowHold.createNewHold(
            orderNumber,
            customerId,
            merchantId,
            driverId,
            totalOrderAmount,
            merchantAmount,
            shippingFeeAmount
        );
        return escrowRepository.save(hold);
    }

    public EscrowHold releaseEscrow(String orderNumber) {
        EscrowHold hold = escrowRepository.findByOrderNumber(orderNumber)
            .orElseThrow(() -> new EscrowNotFoundException("Escrow hold not found for order: " + orderNumber));

        if (hold.status() == EscrowHold.EscrowStatus.RELEASED) {
            return hold;
        }

        MerchantWallet merchantWallet = merchantWalletRepository.findByMerchantId(hold.merchantId())
            .orElseGet(() -> MerchantWallet.createInitial(hold.merchantId()));
        merchantWalletRepository.save(merchantWallet.releaseEscrowToAvailable(hold.merchantAmount()));

        if (hold.driverId() != null && hold.driverId() > 0) {
            DriverWallet driverWallet = driverWalletRepository.findByDriverId(hold.driverId())
                .orElseGet(() -> DriverWallet.createInitial(hold.driverId()));
            driverWalletRepository.save(driverWallet.releaseEscrowToAvailable(hold.shippingFeeAmount()));
        }

        EscrowHold released = hold.markAsReleased();
        return escrowRepository.save(released);
    }

    public void processAutoReleaseJob() {
        List<EscrowHold> pendingHolds = escrowRepository.findPendingAutoReleaseHolds();
        for (EscrowHold hold : pendingHolds) {
            releaseEscrow(hold.orderNumber());
        }
    }
}
