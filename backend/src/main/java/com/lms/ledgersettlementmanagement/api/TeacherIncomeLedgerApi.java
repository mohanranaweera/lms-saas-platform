package com.lms.ledgersettlementmanagement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
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
 * read per related source, whatever the number of entries. The enriched-entry
 * reads it shares with other reports are declared on {@link ReportLedgerApi}.
 */
public interface TeacherIncomeLedgerApi extends ReportLedgerApi {

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
