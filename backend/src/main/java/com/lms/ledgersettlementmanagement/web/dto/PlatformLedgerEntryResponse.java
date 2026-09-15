package com.lms.ledgersettlementmanagement.web.dto;

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
 * @param tenantName best-effort tenant display name, resolved via {@code
 * TenantLookupApi#resolveTenantSummaries} - {@code null} if the tenant id no
 * longer resolves to a real {@code tenant} row.
 */
public record PlatformLedgerEntryResponse(UUID id, UUID tenantId, String tenantName, UUID orderId, UUID paymentId,
		LedgerEntryType entryType, BigDecimal amount, UUID reversesEntryId, Instant createdAt) {

	public PlatformLedgerEntryResponse {
		if (tenantId == null) {
			throw new IllegalArgumentException("tenantId must not be null");
		}
	}

}
