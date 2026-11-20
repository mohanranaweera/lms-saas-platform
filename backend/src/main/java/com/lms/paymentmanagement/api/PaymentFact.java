package com.lms.paymentmanagement.api;

import java.util.UUID;

/**
 * RPT-01 - the few immutable facts a revenue report needs about one {@code
 * payment} row: its order, its currency and its {@code gateway_reference}
 * (the confirmation-path prefix convention {@code FREE-}/{@code
 * STAFF_GRANTED-}/{@code SLIP-}/{@code MANUAL-}/gateway, from which
 * {@code ledger-settlement-management} derives the payment method).
 */
public record PaymentFact(UUID paymentId, UUID orderId, String currency, String gatewayReference) {

}
