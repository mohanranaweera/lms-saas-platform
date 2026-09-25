package com.lms.ledgersettlementmanagement.web.dto;

import com.lms.ledgersettlementmanagement.api.LedgerHistoryEntryView;
import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.PaymentOperationalState;
import com.lms.ledgersettlementmanagement.domain.LedgerEntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Cross-tenant, platform-level ledger row response DTO (PADASH-2, plan §7) -
 * a genuinely new DTO, not a reuse of {@link LedgerHistoryEntryResponse}
 * (which has no tenant field and must not be widened for this use, per
 * {@code docs/api/ledger-settlement-management.md}'s approved-contract
 * discipline). Never the {@code LedgerEntry} JPA entity itself.
 *
 * <p>Wave 6 §4 extends this response with the same {@code courseId}/{@code
 * courseTitle}/{@code billingPeriodId}/{@code operationalState}/{@code
 * method}/{@code reference} fields as {@link LedgerHistoryEntryResponse},
 * resolved the same way (each tenant's own {@code payment-management}/
 * {@code course-management} data, via {@code PlatformAdminLedgerQueryService}
 * calling the same per-tenant {@link
 * com.lms.ledgersettlementmanagement.service.LedgerViewEnrichmentService}
 * this module's tenant-scoped dashboard uses - never a cross-tenant read
 * path).
 *
 * @param tenantName best-effort tenant display name, resolved via {@code
 * TenantLookupApi#resolveTenantSummaries} - {@code null} if the tenant id no
 * longer resolves to a real {@code tenant} row.
 */
public record PlatformLedgerEntryResponse(UUID id, UUID tenantId, String tenantName, UUID orderId, UUID paymentId,
		LedgerEntryType entryType, BigDecimal amount, UUID reversesEntryId, Instant createdAt, UUID courseId,
		String courseTitle, UUID billingPeriodId, PaymentOperationalState operationalState, PaymentMethod method,
		String reference) {

	public PlatformLedgerEntryResponse {
		if (tenantId == null) {
			throw new IllegalArgumentException("tenantId must not be null");
		}
	}

	public static PlatformLedgerEntryResponse from(LedgerHistoryEntryView view, UUID tenantId, String tenantName) {
		return new PlatformLedgerEntryResponse(view.id(), tenantId, tenantName, view.orderId(), view.paymentId(),
				view.entryType(), view.amount(), view.reversesEntryId(), view.createdAt(), view.courseId(),
				view.courseTitle(), view.billingPeriodId(), view.operationalState(), view.method(),
				view.reference());
	}

}
