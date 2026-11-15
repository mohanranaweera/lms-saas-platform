package com.lms.ledgersettlementmanagement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * FIN-01: published synchronously by {@code LedgerEntryService} right after a
 * {@code ledger_entry} row is written, inside the same transaction, so the
 * Finance account book can post the fact (student payment / refund) to a
 * tenant finance account atomically with the ledger write. Listeners must not
 * decide anything about enrollment or entitlements; the ledger entry itself is
 * already final when this fires.
 *
 * @param entryType {@code PAYMENT_CONFIRMED} or {@code REFUND}
 * @param amount signed as stored on the ledger row (REFUND entries are negative)
 * @param reversesEntryId the PAYMENT_CONFIRMED entry a REFUND reverses, else {@code null}
 */
public record LedgerEntryRecordedEvent(UUID tenantId, UUID entryId, UUID orderId, UUID paymentId, String entryType,
		BigDecimal amount, UUID reversesEntryId, Instant recordedAt) {

	public boolean isRefund() {
		return "REFUND".equals(entryType);
	}

}
