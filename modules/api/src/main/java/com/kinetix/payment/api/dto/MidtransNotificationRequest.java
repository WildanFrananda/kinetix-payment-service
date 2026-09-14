package com.kinetix.payment.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MidtransNotificationRequest(
    @JsonProperty("order_id") String orderId,
    @JsonProperty("status_code") String statusCode,
    @JsonProperty("gross_amount") String grossAmount,
    @JsonProperty("signature_key") String signatureKey,
    @JsonProperty("transaction_status") String transactionStatus
) {}
