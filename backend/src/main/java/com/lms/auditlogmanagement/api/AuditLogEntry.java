package com.lms.auditlogmanagement.api;

import java.util.Map;
import java.util.UUID;

/**
 * The write payload accepted by {@link AuditLogApi#record(AuditLogEntry)}.
 * {@code tenantId}/{@code occurredAt} are deliberately absent - both are
 * resolved internally by the implementation from the trusted {@code
 * TenantContext}/{@code Instant.now()}, never accepted from a caller (per
 * this interface's own javadoc and root {@code CLAUDE.md}'s tenant-isolation
 * rule).
 *
 * @param actorId the authenticated user who performed the audited action -
 * never null.
 * @param action a short, stable machine-readable action name (e.g. {@code
 * "payment_slip.approved"}, {@code "payment_slip.approved_with_override"},
 * {@code "payment_slip.rejected"}) - never null/blank.
 * @param targetEntity the name of the table/aggregate the action targeted
 * (e.g. {@code "payment_slip"}) - never null/blank.
 * @param targetId the id of the specific row the action targeted - never
 * null.
 * @param reason optional human-supplied justification (mandatory, at the
 * calling service's own discretion, for an override action) - {@code null}
 * for an action that carries no reason; if supplied, must not be blank.
 * @param metadata optional structured detail (e.g. which flag(s) were
 * overridden) - {@code null} or empty for an action with no extra detail.
 */
public record AuditLogEntry(UUID actorId, String action, String targetEntity, UUID targetId, String reason,
		Map<String, Object> metadata) {

	public AuditLogEntry {
		if (actorId == null) {
			throw new IllegalArgumentException("actorId must not be null");
		}
		if (action == null || action.isBlank()) {
			throw new IllegalArgumentException("action must not be null/blank");
		}
		if (targetEntity == null || targetEntity.isBlank()) {
			throw new IllegalArgumentException("targetEntity must not be null/blank");
		}
		if (targetId == null) {
			throw new IllegalArgumentException("targetId must not be null");
		}
		if (reason != null && reason.isBlank()) {
			throw new IllegalArgumentException("reason must not be blank if supplied - pass null instead");
		}
		metadata = (metadata == null) ? null : Map.copyOf(metadata);
	}

	/** Convenience factory for the common "no reason, no metadata" case (a plain approve/reject). */
	public static AuditLogEntry of(UUID actorId, String action, String targetEntity, UUID targetId) {
		return new AuditLogEntry(actorId, action, targetEntity, targetId, null, null);
	}

}
