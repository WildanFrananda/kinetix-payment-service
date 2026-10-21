package com.kinetix.payment.api.config;

import com.kinetix.payment.application.EscrowService;
import com.kinetix.payment.application.LiabilitySnapshotRecordService;
import com.kinetix.payment.application.LiabilitySnapshotService;
import com.kinetix.payment.application.TopUpLimits;
import com.kinetix.payment.application.TopUpService;
import com.kinetix.payment.application.WalletService;
import com.kinetix.payment.domain.port.*;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BeanConfig {
    @Bean
    public WalletService walletService(
        CustomerWalletRepositoryPort customerWalletRepository,
        MerchantWalletRepositoryPort merchantWalletRepository,
        DriverWalletRepositoryPort driverWalletRepository
    ) {
        return new WalletService(customerWalletRepository, merchantWalletRepository, driverWalletRepository);
    }

    @Bean
    public EscrowService escrowService(
        EscrowRepositoryPort escrowRepository,
        CustomerWalletRepositoryPort customerWalletRepository,
        MerchantWalletRepositoryPort merchantWalletRepository,
        DriverWalletRepositoryPort driverWalletRepository,
        SuspenseWalletRepositoryPort suspenseWalletRepository,
        PaymentTransactionRepositoryPort paymentTransactionRepository,
        EscrowIdempotencyRepositoryPort idempotencyRepository,
        TransactionRunnerPort transactionRunner,
        AdvisoryLockPort advisoryLock
    ) {
        return new EscrowService(
            escrowRepository,
            customerWalletRepository,
            merchantWalletRepository,
            driverWalletRepository,
            suspenseWalletRepository,
            paymentTransactionRepository,
            idempotencyRepository,
            transactionRunner,
            advisoryLock
        );
    }

    @Bean
    public LiabilitySnapshotService liabilitySnapshotService(
        LiabilitySnapshotPort liabilitySnapshots,
        LiabilitySnapshotRecordPort liabilitySnapshotRecords
    ) {
        return new LiabilitySnapshotService(liabilitySnapshots, liabilitySnapshotRecords);
    }

    @Bean
    public LiabilitySnapshotRecordService liabilitySnapshotRecordService(
        LiabilitySnapshotRecordPort liabilitySnapshotRecords
    ) {
        return new LiabilitySnapshotRecordService(liabilitySnapshotRecords);
    }

    @Bean
    public TopUpLimits topUpLimits(
        @Value("${kinetix.top-up.maximum-amount}") BigDecimal maximumTopUp,
        @Value("${kinetix.top-up.maximum-customer-balance}") BigDecimal maximumCustomerBalance
    ) {
        return new TopUpLimits(maximumTopUp, maximumCustomerBalance);
    }

    @Bean
    public TopUpService topUpService(
        PaymentTransactionRepositoryPort transactionRepository,
        CustomerWalletRepositoryPort customerWalletRepository,
        PaymentGatewayPort paymentGatewayPort,
        TransactionRunnerPort transactionRunner,
        AdvisoryLockPort advisoryLock,
        TopUpLimits topUpLimits,
        TopUpAlarmPort topUpAlarm
    ) {
        return new TopUpService(
            transactionRepository,
            customerWalletRepository,
            paymentGatewayPort,
            transactionRunner,
            advisoryLock,
            topUpLimits,
            topUpAlarm
        );
    }
}
