package com.lms.ledgersettlementmanagement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * RPT-01 (Teacher Income Summary) - the narrow, read-only ledger contract the
 * reporting module depends on. Tenant identity always comes from the resolved
 * {@link com.lms.common.tenant.TenantContext}; no method accepts a tenant id.
 *
 * <p><b>Authorization is the caller's job:</b> these reads are tenant-scoped
 * but NOT permission-checked. Every caller must first require {@code
 * FINANCE_EXPENSES}/{@code VIEW} (as {@code TeacherIncomeReportService} does)
 * before exposing any result.
 * Nothing here writes: no ledger, payment or settlement row is ever created or
 * changed by these reads. Batched (no N+1): one ledger read plus one batched
 * read per related source, whatever the number of entries.
 */
public interface TeacherIncomeLedgerApi {

	/**
	 * DATES mode (and MONTHS-mode legacy detection): every {@code
	 * PAYMENT_CONFIRMED} / {@code REFUND} entry whose {@code created_at} falls
	 * in any of {@code windows}, oldest first, fully enriched.
	 * @throws ReportScopeTooLargeException if more than {@code maxEntries}
	 * entries are in scope (counted before anything is loaded or enriched).
	 */
	List<ReportLedgerEntry> findEntriesCreatedIn(List<InstantRange> windows, int maxEntries);

	/**
	 * MONTHS mode: every entry (confirmed and refunds, whatever their dates) of
	 * the orders that have at least one line billed in {@code months} (MONTHLY
	 * line period or LESSON line's lesson billing month), oldest first.
	 * @throws ReportScopeTooLargeException if more than {@code maxEntries}
	 * entries are in scope (counted before anything is loaded or enriched).
	 */
	List<ReportLedgerEntry> findEntriesForBillingMonths(Set<YearMonth> months, int maxEntries);

	/** Every revenue-share rate row (append-only, effective-dated) of the given teachers. */
	List<ShareRate> findShareRates(Collection<UUID> teacherIds);

	/**
	 * Stored {@code PAID} {@code teacher_settlement} rows (REGULAR and
	 * ADJUSTMENT, never recomputed) of the given teachers whose {@code paid_at}
	 * falls in any of {@code paidWindows}.
	 */
	List<PaidSettlement> findPaidSettlements(Collection<UUID> teacherIds, List<InstantRange> paidWindows);

	record ShareRate(UUID teacherId, BigDecimal sharePercent, LocalDate effectiveFrom) {

	}

	/** {@code kind} = {@code REGULAR|ADJUSTMENT}; {@code shareAmount} is the stored figure. */
	record PaidSettlement(UUID settlementId, UUID teacherId, String kind, BigDecimal shareAmount, String currency,
			Instant paidAt) {

	}

}
