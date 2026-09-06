package com.kinetix.payment.domain.port;

import com.kinetix.payment.domain.entity.PaymentTransaction;
import java.math.BigDecimal;

public interface PaymentGatewayPort {
    PaymentTransaction createTopUpTransaction(String customerPrincipalId, BigDecimal amount, PaymentTransaction.PaymentMethod method);
    PaymentTransaction processCheckoutPayment(String orderNumber, String customerPrincipalId, BigDecimal amount, PaymentTransaction.PaymentMethod method);
}
