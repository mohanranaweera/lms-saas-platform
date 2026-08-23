package com.lms.ledgersettlementmanagement.service;

import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.ledgersettlementmanagement.api.LedgerEntryApi;
import com.lms.ledgersettlementmanagement.api.LedgerHistoryEntryView;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs the two read-only endpoints in {@code
 * com.lms.ledgersettlementmanagement.web} - kept as a thin, domain-local
 * orchestration layer over {@link LedgerEntryApi} (this module's own write
 * API, also usable for its own reads) and {@link PaymentStatusApi} (the
 * cross-module read needed to scope a student's own history to their own
 * orders, per {@code .claude/rules/payments.md}'s "ledger-derived, never
 * order/payment-status-derived for 'is this paid'" rule - this call is
 * purely for order-id scoping, not for determining paid/unpaid state).
 */
@Service
@Transactional(readOnly = true)
public class LedgerQueryService {

	private final LedgerEntryApi ledgerEntryApi;

	private final PaymentStatusApi paymentStatusApi;

	private final PermissionCheckService permissionCheckService;

	public LedgerQueryService(LedgerEntryApi ledgerEntryApi, PaymentStatusApi paymentStatusApi,
			PermissionCheckService permissionCheckService) {
		this.ledgerEntryApi = ledgerEntryApi;
		this.paymentStatusApi = paymentStatusApi;
		this.permissionCheckService = permissionCheckService;
	}

	/** Student's own confirmed-payment history, ledger-derived (PAY-3). */
	public List<LedgerHistoryEntryView> getHistoryForStudent(UUID studentId) {
		List<UUID> orderIds = paymentStatusApi.findOrderIdsForStudent(studentId);
		return ledgerEntryApi.findHistoryForOrders(orderIds);
	}

	/** Tenant-admin Payment Dashboard - {@code PAYMENTS_SLIPS}/{@code VIEW}-gated, tenant-scoped only. */
	public Page<LedgerHistoryEntryView> getDashboard(Pageable pageable) {
		permissionCheckService.requirePermission(DomainArea.PAYMENTS_SLIPS, PermissionAction.VIEW);
		return ledgerEntryApi.findDashboard(pageable);
	}

}
