package com.lms.auditlogmanagement.web.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Platform-level audit log response DTO (PADASH-2, plan §7) - identical in
 * shape to {@link AuditLogEntryResponse} plus a mandatory {@code tenantId}/
 * {@code tenantName} discriminator, per plan §14/§15's "every cross-tenant
 * response row carries an explicit tenant discriminator" rule. Never the
 * {@code AuditLog} JPA entity itself.
 *
 * @param tenantName best-effort tenant display name, resolved via {@code
 * TenantLookupApi#resolveTenantSummaries} - {@code null} if the tenant id no
 * longer resolves to a real {@code tenant} row.
 */
public record PlatformAuditLogEntryResponse(UUID id, UUID tenantId, String tenantName, UUID actorId, String action,
		String targetEntity, UUID targetId, String reason, Map<String, Object> metadata, Instant occurredAt) {

	public PlatformAuditLogEntryResponse {
		if (tenantId == null) {
			throw new IllegalArgumentException("tenantId must not be null");
		}
	}

}
