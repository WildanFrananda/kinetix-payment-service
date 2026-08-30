package com.kinetix.payment.domain.port;

import com.kinetix.payment.domain.entity.PaymentTransaction;
import java.math.BigDecimal;

public interface PaymentGatewayPort {
    PaymentTransaction createTopUpTransaction(Long customerId, BigDecimal amount, PaymentTransaction.PaymentMethod method);
    PaymentTransaction processCheckoutPayment(String orderNumber, Long customerId, BigDecimal amount, PaymentTransaction.PaymentMethod method);
}
