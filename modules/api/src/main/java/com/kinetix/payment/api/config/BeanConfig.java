package com.kinetix.payment.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kinetix.payment.application.EscrowService;
import com.kinetix.payment.application.TopUpService;
import com.kinetix.payment.application.WalletService;
import com.kinetix.payment.domain.port.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BeanConfig {
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public WalletService walletService(
        CustomerWalletRepositoryPort customerWalletRepository,
        MerchantWalletRepositoryPort merchantWalletRepository,
        DriverWalletRepositoryPort driverWalletRepository,
        TransactionRunnerPort transactionRunner,
        AdvisoryLockPort advisoryLock
    ) {
        return new WalletService(
            customerWalletRepository,
            merchantWalletRepository,
            driverWalletRepository,
            transactionRunner,
            advisoryLock
        );
    }

    @Bean
    public EscrowService escrowService(
        EscrowRepositoryPort escrowRepository,
        CustomerWalletRepositoryPort customerWalletRepository,
        MerchantWalletRepositoryPort merchantWalletRepository,
        DriverWalletRepositoryPort driverWalletRepository,
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
            paymentTransactionRepository,
            idempotencyRepository,
            transactionRunner,
            advisoryLock
        );
    }

    @Bean
    public TopUpService topUpService(
        PaymentTransactionRepositoryPort transactionRepository,
        CustomerWalletRepositoryPort customerWalletRepository,
        PaymentGatewayPort paymentGatewayPort,
        TransactionRunnerPort transactionRunner,
        AdvisoryLockPort advisoryLock
    ) {
        return new TopUpService(
            transactionRepository,
            customerWalletRepository,
            paymentGatewayPort,
            transactionRunner,
            advisoryLock
        );
    }
}
