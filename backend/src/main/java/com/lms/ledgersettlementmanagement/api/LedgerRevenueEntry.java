package com.lms.ledgersettlementmanagement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wave 7 (§4) - one authoritative {@code ledger_entry} row. {@code refund} is
 * {@code true} for a {@code REFUND} entry and {@code false} for {@code
 * PAYMENT_CONFIRMED} - a plain flag, so consumers never import this module's
 * {@code domain} enum. {@code amount} keeps the ledger's own sign convention
 * (positive confirmed, negative refund).
 *
 * <p>ADR-029: {@code allocations} = the per-course split of this entry
 * (legacy order: one allocation of the full amount to its course; line order:
 * each course's line-total share, refunds pro rata; postage is never
 * allocated, so allocations may sum to less than {@code amount}). {@code
 * courseId} = the order's single course, {@code null} for a multi-course
 * order. Totals and counts must be computed per entry, never per allocation.
 */
public record LedgerRevenueEntry(UUID entryId, UUID orderId, boolean refund, BigDecimal amount, Instant createdAt,
		UUID courseId, List<LedgerRevenueAllocation> allocations) {

	/** Legacy shape: the full amount allocated to {@code courseId} (none if {@code null}). */
	public LedgerRevenueEntry(UUID entryId, UUID orderId, boolean refund, BigDecimal amount, Instant createdAt,
			UUID courseId) {
		this(entryId, orderId, refund, amount, createdAt, courseId,
				courseId == null ? List.of() : List.of(new LedgerRevenueAllocation(courseId, amount)));
	}

	public LedgerRevenueEntry {
		allocations = allocations == null ? List.of() : List.copyOf(allocations);
	}

}
