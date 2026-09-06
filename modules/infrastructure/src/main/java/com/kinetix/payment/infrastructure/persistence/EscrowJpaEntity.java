package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.domain.entity.EscrowHold;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "escrow_holds")
public class EscrowJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", nullable = false, unique = true)
    private String orderNumber;

    @Column(name = "customer_principal_id", nullable = false)
    private String customerPrincipalId;

    @Column(name = "merchant_principal_id", nullable = false)
    private String merchantPrincipalId;

    @Column(name = "driver_principal_id")
    private String driverPrincipalId;

    @Column(name = "total_order_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalOrderAmount;

    @Column(name = "merchant_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal merchantAmount;

    @Column(name = "shipping_fee_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal shippingFeeAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private EscrowHold.EscrowStatus status;

    @Column(name = "auto_release_at", nullable = false)
    private Instant autoReleaseAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    public EscrowJpaEntity() {}

    public EscrowJpaEntity(Long id, String orderNumber, String customerPrincipalId, String merchantPrincipalId, String driverPrincipalId, BigDecimal totalOrderAmount, BigDecimal merchantAmount, BigDecimal shippingFeeAmount, EscrowHold.EscrowStatus status, Instant autoReleaseAt, Instant createdAt, Instant releasedAt) {
        this.id = id;
        this.orderNumber = orderNumber;
        this.customerPrincipalId = customerPrincipalId;
        this.merchantPrincipalId = merchantPrincipalId;
        this.driverPrincipalId = driverPrincipalId;
        this.totalOrderAmount = totalOrderAmount;
        this.merchantAmount = merchantAmount;
        this.shippingFeeAmount = shippingFeeAmount;
        this.status = status;
        this.autoReleaseAt = autoReleaseAt;
        this.createdAt = createdAt;
        this.releasedAt = releasedAt;
    }

    public Long getId() { return id; }
    public String getOrderNumber() { return orderNumber; }
    public String getCustomerPrincipalId() { return customerPrincipalId; }
    public String getMerchantPrincipalId() { return merchantPrincipalId; }
    public String getDriverPrincipalId() { return driverPrincipalId; }
    public BigDecimal getTotalOrderAmount() { return totalOrderAmount; }
    public BigDecimal getMerchantAmount() { return merchantAmount; }
    public BigDecimal getShippingFeeAmount() { return shippingFeeAmount; }
    public EscrowHold.EscrowStatus getStatus() { return status; }
    public Instant getAutoReleaseAt() { return autoReleaseAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReleasedAt() { return releasedAt; }
}
