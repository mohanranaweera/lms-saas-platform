package com.lms.auditlogmanagement.web.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Read-side response DTO for {@code AuditLogController} - mirrors {@code
 * com.lms.ledgersettlementmanagement.web.dto.LedgerHistoryEntryResponse}'s
 * plain-record convention. Never the {@code AuditLog} JPA entity itself, per
 * {@code backend/CLAUDE.md}'s "do not expose JPA entities directly" rule.
 *
 * @param actorDisplayName best-effort display name (the actor's {@code
 * tenant_user.email}, resolved via {@code UserProvisioningApi
 * #findTenantUserSummaries}) - {@code null} if the actor id no longer
 * resolves to a {@code tenant_user} row in the caller's own tenant.
 */
public record AuditLogEntryResponse(UUID id, UUID actorId, String actorDisplayName, String action,
		String targetEntity, UUID targetId, String reason, Map<String, Object> metadata, Instant occurredAt) {

}
