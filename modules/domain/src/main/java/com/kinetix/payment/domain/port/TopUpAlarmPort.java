package com.kinetix.payment.domain.port;

import com.kinetix.payment.domain.entity.PaymentTransaction;

public interface TopUpAlarmPort {
    void setAside(PaymentTransaction transaction);
}
