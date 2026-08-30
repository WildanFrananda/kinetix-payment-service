package com.kinetix.payment.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kinetix.payment.application.EscrowService;
import com.kinetix.payment.application.PaymentGatewayService;
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
        DriverWalletRepositoryPort driverWalletRepository
    ) {
        return new WalletService(customerWalletRepository, merchantWalletRepository, driverWalletRepository);
    }

    @Bean
    public EscrowService escrowService(
        EscrowRepositoryPort escrowRepository,
        CustomerWalletRepositoryPort customerWalletRepository,
        MerchantWalletRepositoryPort merchantWalletRepository,
        DriverWalletRepositoryPort driverWalletRepository
    ) {
        return new EscrowService(escrowRepository, customerWalletRepository, merchantWalletRepository, driverWalletRepository);
    }

    @Bean
    public PaymentGatewayService paymentGatewayService(
        PaymentGatewayPort paymentGatewayPort,
        PaymentTransactionRepositoryPort transactionRepository
    ) {
        return new PaymentGatewayService(paymentGatewayPort, transactionRepository);
    }
}
