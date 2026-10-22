package com.lms.ledgersettlementmanagement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * RPT-01 - one authoritative {@code ledger_entry} row enriched for revenue
 * reporting. Every figure comes from immutable sources: the ledger row, the
 * entry's payment ({@code currency}, {@code method} derived from its {@code
 * gateway_reference} prefix), the ADR-029 {@code RevenueAllocator} course
 * allocations (A2 basis) split to order lines, and stored settlement items.
 *
 * @param refund {@code true} for a {@code REFUND} entry (refund or chargeback).
 * @param amount the ledger's own signed amount (positive confirmed, negative refund).
 * @param currency the entry's payment currency ({@code null} only if the entry
 * has no payment row).
 * @param lineOrder {@code false} for a legacy (line-less) order, whose single
 * allocation has no lines and therefore no billing month.
 * @param courses the entry's course allocations ({@code amount} sums to the
 * attributable part of the entry; {@code entry amount - sum} = facility fees +
 * postage = institute service fees).
 */
public record ReportLedgerEntry(UUID entryId, UUID orderId, UUID paymentId, boolean refund, BigDecimal amount,
		Instant createdAt, UUID reversesEntryId, String currency, PaymentMethod method, boolean lineOrder,
		List<ReportCourseAllocation> courses) {

	public ReportLedgerEntry {
		courses = courses == null ? List.of() : List.copyOf(courses);
	}

	/**
	 * One (ledger entry, course) allocation.
	 *
	 * @param lines the allocation split to the course's order lines (spec 2-R.3:
	 * pro rata by line share, remainder to the largest line, ties lowest {@code
	 * line_no}; zero parts omitted) - empty for a legacy order. The line amounts
	 * always sum exactly to {@code amount}.
	 * @param settlement the settlement that already includes this allocation, or
	 * {@code null} if it is unsettled.
	 */
	public record ReportCourseAllocation(UUID courseId, BigDecimal amount, List<ReportLineAllocation> lines,
			SettledAllocation settlement) {

		public ReportCourseAllocation {
			lines = lines == null ? List.of() : List.copyOf(lines);
		}

	}

	/**
	 * One order line's part of a course allocation.
	 *
	 * @param scope {@code MONTHLY|MODULE|LESSON}.
	 * @param billingYear MONTHLY: the line's period; LESSON: the lesson's current
	 * billing month (editable - documented limitation); otherwise {@code null}.
	 */
	public record ReportLineAllocation(UUID lineId, int lineNo, String scope, Integer billingYear,
			Integer billingMonth, BigDecimal amount) {

		public boolean hasBillingMonth() {
			return billingYear != null && billingMonth != null;
		}

	}

	/**
	 * The stored settlement item covering an allocation (never recomputed).
	 *
	 * @param itemAmount the stored {@code teacher_settlement_item.amount}.
	 * @param status {@code CALCULATED} or {@code PAID}.
	 */
	public record SettledAllocation(UUID settlementId, UUID teacherId, BigDecimal sharePercent,
			BigDecimal itemAmount, String status) {

	}

}
