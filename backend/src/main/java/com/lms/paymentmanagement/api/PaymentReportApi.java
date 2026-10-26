package com.lms.paymentmanagement.api;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RPT-02 (Student Payment Report) - narrow, read-only, batched reads of
 * payment-management's immutable order / receipt snapshots. Tenant identity
 * always comes from the resolved {@link com.lms.common.tenant.TenantContext};
 * no method accepts a tenant id, and ids of another tenant are simply absent
 * from the result (never an error, never distinguishable from unknown ids).
 *
 * <p><b>Authorization is the caller's job:</b> these reads are tenant-scoped
 * but NOT permission-checked. Every caller must first require {@code
 * FINANCE_EXPENSES}/{@code VIEW} before exposing any result. Nothing here
 * writes. Every method reads in id chunks (never one query per id).
 */
public interface PaymentReportApi {

	/** {@code orderId -> student_order.student_id} for every order of the current tenant in {@code orderIds}. */
	Map<UUID, UUID> findOrderStudentIds(Collection<UUID> orderIds);

	/** The V90 per-(order, course) fee / postage snapshots of {@code orderIds} (pre-V90 and legacy orders have none). */
	List<OrderCourseCharge> findOrderCourseCharges(Collection<UUID> orderIds);

	/** The ADR-030 manual receipts whose CONFIRMED payment is one of {@code paymentIds}. */
	List<ManualReceiptFact> findManualReceiptsByPaymentIds(Collection<UUID> paymentIds);

	/**
	 * Number of the current tenant's manual receipts whose {@code received_on}
	 * lies in {@code [fromInclusive, toInclusive]} - a count only, so callers can
	 * enforce their size bound before {@link
	 * #findManualReceiptPaymentIdsReceivedBetween} loads anything.
	 */
	long countManualReceiptsReceivedBetween(LocalDate fromInclusive, LocalDate toInclusive);

	/**
	 * Count of the current tenant's manual receipts whose {@code received_on}
	 * lies in {@code [fromInclusive, toInclusive]} and whose payment id is in
	 * {@code paymentIds}. Used to remove already-loaded ledger confirmations from
	 * the additional backdated-receipt workload count.
	 */
	long countManualReceiptsReceivedBetweenForPaymentIds(LocalDate fromInclusive, LocalDate toInclusive,
			Collection<UUID> paymentIds);

	/**
	 * Payment ids (only that column is read) of the current tenant's manual
	 * receipts whose {@code received_on} lies in {@code [fromInclusive,
	 * toInclusive]} (receipts may be backdated, so their ledger time can lie
	 * outside that range). Callers count first ({@link
	 * #countManualReceiptsReceivedBetween}).
	 */
	List<UUID> findManualReceiptPaymentIdsReceivedBetween(LocalDate fromInclusive, LocalDate toInclusive);

}
