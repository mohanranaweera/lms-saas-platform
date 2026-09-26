package com.lms.ledgersettlementmanagement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Wave 7 (§4) - one authoritative {@code ledger_entry} row, resolved to the
 * course its order was placed for. {@code refund} is {@code true} for a
 * {@code REFUND} entry and {@code false} for {@code PAYMENT_CONFIRMED} - a
 * plain flag, so consumers never import this module's {@code domain} enum.
 * {@code amount} keeps the ledger's own sign convention (positive confirmed,
 * negative refund). {@code courseId} is {@code null} only if the order no
 * longer resolves (structurally unreachable - composite-FK-backed).
 */
public record LedgerRevenueEntry(UUID entryId, UUID orderId, boolean refund, BigDecimal amount, Instant createdAt,
		UUID courseId) {

}
