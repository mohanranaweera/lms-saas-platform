package com.lms.ledgersettlementmanagement.api;

import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * RPT-01 / RPT-02 - the narrow, read-only ledger reads shared by the revenue
 * reports (Teacher Income Summary, Student Payment Report): authoritative
 * {@code ledger_entry} rows enriched into {@link ReportLedgerEntry} (payment
 * currency and method, ADR-029 + A2 course allocations split to order lines).
 * Tenant identity always comes from the resolved {@link
 * com.lms.common.tenant.TenantContext}; no method accepts a tenant id.
 *
 * <p><b>Authorization is the caller's job:</b> these reads are tenant-scoped
 * but NOT permission-checked. Every caller must first require {@code
 * FINANCE_EXPENSES}/{@code VIEW} (as both report services do) before exposing
 * any result. Nothing here writes: no ledger, payment or settlement row is
 * ever created or changed. Batched (no N+1): one ledger read plus one batched
 * read per related source, whatever the number of entries.
 */
public interface ReportLedgerApi {

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

	/**
	 * RPT-02 (spec 3-R.4): the {@code PAYMENT_CONFIRMED} entries of {@code
	 * paymentIds} (any date), oldest first, fully enriched - used to add
	 * backdated manual-receipt confirmations found by their receipt date.
	 * @throws ReportScopeTooLargeException if more than {@code maxEntries}
	 * entries are in scope (counted before anything is loaded or enriched).
	 */
	List<ReportLedgerEntry> findConfirmationsForPayments(Collection<UUID> paymentIds, int maxEntries);

	/** Every confirmation/refund entry of the current tenant's supplied orders. */
	List<ReportLedgerEntry> findEntriesForOrders(Collection<UUID> orderIds, int maxEntries);

}
