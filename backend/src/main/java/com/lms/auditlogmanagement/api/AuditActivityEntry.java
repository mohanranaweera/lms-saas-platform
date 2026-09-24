package com.lms.auditlogmanagement.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Cross-module read projection of one {@code audit_log} row, returned by
 * {@link AuditLogApi#findForTarget(String, UUID, org.springframework.data.domain.Pageable)}
 * (Wave 3 - per-student/per-teacher Activity tab). Deliberately a distinct
 * type from {@code com.lms.auditlogmanagement.web.dto.AuditLogEntryResponse}
 * - that DTO lives in this module's own {@code web.dto} package and is not a
 * cross-module contract; this one lives in {@code api} specifically so
 * {@code user-management} (the first cross-module caller) can depend on it
 * without importing a foreign {@code web}/{@code domain} package, per
 * {@code .claude/rules/architecture.md}.
 *
 * @param actorDisplayName best-effort display name (the actor's {@code
 * tenant_user.email}) - {@code null} if the actor id no longer resolves to a
 * {@code tenant_user} row in the caller's own tenant.
 */
public record AuditActivityEntry(UUID id, UUID actorId, String actorDisplayName, String action, String targetEntity,
		UUID targetId, String reason, Map<String, Object> metadata, Instant occurredAt) {

}
