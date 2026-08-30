package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.PaymentTransaction;
import com.kinetix.payment.domain.port.PaymentGatewayPort;
import com.kinetix.payment.domain.port.PaymentTransactionRepositoryPort;
import java.math.BigDecimal;

public class PaymentGatewayService {
    private final PaymentGatewayPort paymentGatewayPort;
    private final PaymentTransactionRepositoryPort transactionRepository;

    public PaymentGatewayService(
        PaymentGatewayPort paymentGatewayPort,
        PaymentTransactionRepositoryPort transactionRepository
    ) {
        this.paymentGatewayPort = paymentGatewayPort;
        this.transactionRepository = transactionRepository;
    }

    public PaymentTransaction topUpViaGateway(Long customerId, BigDecimal amount, PaymentTransaction.PaymentMethod method) {
        PaymentTransaction transaction = paymentGatewayPort.createTopUpTransaction(customerId, amount, method);
        return transactionRepository.save(transaction);
    }

    public PaymentTransaction processCheckoutPayment(String orderNumber, Long customerId, BigDecimal amount, PaymentTransaction.PaymentMethod method) {
        PaymentTransaction transaction = paymentGatewayPort.processCheckoutPayment(orderNumber, customerId, amount, method);
        return transactionRepository.save(transaction);
    }
}
