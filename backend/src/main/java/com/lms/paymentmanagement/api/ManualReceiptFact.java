package com.lms.paymentmanagement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * RPT-02: the immutable facts of one ADR-030 staff-recorded manual payment
 * receipt as a report needs them.
 *
 * @param paymentMethod how the money was received: {@code CASH|BANK_TRANSFER|CARD}.
 * @param receivedOn the tenant-local date the money was received (a date, no time).
 * @param expectedAmount the order total the receipt settled.
 * @param staffAdjustmentAmount {@code expectedAmount - receivedAmount} (&gt;= 0).
 * @param receivedAmount the cash recorded (= the payment / ledger amount).
 * @param recordedAt when staff recorded it.
 */
public record ManualReceiptFact(UUID receiptId, UUID paymentId, UUID orderId, String paymentMethod,
		LocalDate receivedOn, String currency, BigDecimal expectedAmount, BigDecimal staffAdjustmentAmount,
		BigDecimal receivedAmount, Instant recordedAt) {

}
