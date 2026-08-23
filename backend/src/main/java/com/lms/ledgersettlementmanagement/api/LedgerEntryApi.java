package com.lms.ledgersettlementmanagement.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * The only contract other domains (namely {@code payment-management}) are
 * permitted to depend on for ledger writes/reads - mirroring {@code
 * coursemanagement.api.CourseLookupApi}'s shape for a different concern.
 * Every method resolves tenant identity from the already-resolved {@link
 * com.lms.common.tenant.TenantContext} - there is no overload that accepts a
 * caller-supplied tenant id.
 */
public interface LedgerEntryApi {

	/**
	 * Writes exactly one {@code PAYMENT_CONFIRMED} entry, called
	 * synchronously by {@code PaymentConfirmationService} inside the same
	 * local transaction that transitions {@code payment.status =
	 * CONFIRMED} - a "paid" state with no ledger row is a data-integrity bug,
	 * never a display nuance (plan §9/§17).
	 * @return the id of the newly-written entry.
	 */
	UUID recordPaymentConfirmed(UUID orderId, UUID paymentId, BigDecimal amount);

	/**
	 * Writes exactly one {@code REFUND} entry, with {@code reversesEntryId}
	 * set to the {@code PAYMENT_CONFIRMED} entry this reverses (resolved
	 * internally from {@code paymentId} - the caller never supplies the
	 * entry id directly). Called synchronously by {@code RefundService}
	 * inside the same local transaction as the new {@code payment_refund}
	 * row.
	 * @return the id of the newly-written reversal entry.
	 * @throws IllegalStateException if no {@code PAYMENT_CONFIRMED} entry
	 * exists for {@code paymentId} in the current tenant (should never
	 * happen for a payment that is genuinely {@code CONFIRMED}).
	 */
	UUID recordRefund(UUID orderId, UUID paymentId, BigDecimal amount);

	/**
	 * Student's own Payment History (PAY-3) - every ledger entry across the
	 * given order ids, most recent first. {@code orderIds} is resolved by
	 * the caller from {@code PaymentStatusApi#findOrderIdsForStudent}, never
	 * from a client-supplied student id here.
	 */
	List<LedgerHistoryEntryView> findHistoryForOrders(List<UUID> orderIds);

	/**
	 * Tenant-admin Payment Dashboard (PAY-3) - every ledger entry in the
	 * caller's own tenant, paginated. No cross-tenant aggregation - a
	 * separate, later Platform-Admin-authorized surface, per plan §6.
	 */
	Page<LedgerHistoryEntryView> findDashboard(Pageable pageable);

}
