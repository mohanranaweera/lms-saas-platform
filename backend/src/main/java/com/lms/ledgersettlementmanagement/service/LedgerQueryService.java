package com.lms.ledgersettlementmanagement.service;

import com.lms.common.error.NotFoundException;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.ledgersettlementmanagement.api.LedgerEntryApi;
import com.lms.ledgersettlementmanagement.api.LedgerHistoryEntryView;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import com.lms.usermanagement.api.StudentLookupApi;
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

	private final StudentLookupApi studentLookupApi;

	public LedgerQueryService(LedgerEntryApi ledgerEntryApi, PaymentStatusApi paymentStatusApi,
			PermissionCheckService permissionCheckService, StudentLookupApi studentLookupApi) {
		this.ledgerEntryApi = ledgerEntryApi;
		this.paymentStatusApi = paymentStatusApi;
		this.permissionCheckService = permissionCheckService;
		this.studentLookupApi = studentLookupApi;
	}

	/** Student's own confirmed-payment history, ledger-derived (PAY-3). */
	public List<LedgerHistoryEntryView> getHistoryForStudent(UUID studentId) {
		List<UUID> orderIds = paymentStatusApi.findOrderIdsForStudent(studentId);
		return ledgerEntryApi.findHistoryForOrders(orderIds);
	}

	/**
	 * Wave 3 staff-facing read: {@code GET
	 * /api/v1/students/{studentProfileId}/ledger}. {@code studentProfileId}
	 * is resolved via {@link StudentLookupApi#resolveUserId} FIRST - an id
	 * that does not resolve in the caller's own tenant is 404, never
	 * 200-with-empty-list.
	 */
	public List<LedgerHistoryEntryView> getHistoryForStudentProfile(UUID studentProfileId) {
		permissionCheckService.requirePermission(DomainArea.PAYMENTS_SLIPS, PermissionAction.VIEW);
		UUID studentId = studentLookupApi.resolveUserId(studentProfileId)
			.orElseThrow(() -> new NotFoundException("Student not found"));
		return getHistoryForStudent(studentId);
	}

	/** Tenant-admin Payment Dashboard - {@code PAYMENTS_SLIPS}/{@code VIEW}-gated, tenant-scoped only. */
	public Page<LedgerHistoryEntryView> getDashboard(Pageable pageable) {
		permissionCheckService.requirePermission(DomainArea.PAYMENTS_SLIPS, PermissionAction.VIEW);
		return ledgerEntryApi.findDashboard(pageable);
	}

}
