package com.lms.auditlogmanagement.api;

import java.util.UUID;

/**
 * The only contract other domains may depend on for durable audit logging
 * (MVP-011 §21 item 2, option (A) - product owner approved; retroactively
 * documented in {@code docs/adr/ADR-012-audit-log-slice-and-slip-enrollment-activation.md}).
 * Deliberately
 * minimal: a single write method - this module's read path (MVP-019,
 * {@code AuditLogQueryService}/{@code AuditLogController}) queries {@code
 * AuditLogRepository} directly rather than through this interface, since a
 * read is not a cross-module write contract; this interface itself still
 * has no read/query method and none is planned.
 *
 * <p>{@link #record(AuditLogEntry)} resolves {@code tenant_id} from the
 * already-resolved {@link com.lms.common.tenant.TenantContext} and {@code
 * occurred_at} from {@code Instant.now()} internally - there is no overload
 * that accepts a caller-supplied tenant id or timestamp.
 *
 * <p>Implementations MUST participate in the caller's existing transaction
 * (Spring's default {@code REQUIRED} propagation) rather than opening a new
 * one - callers that need the audit write to be atomic with a state
 * transition (e.g. {@code SlipReviewService}'s override-approval path) rely
 * on this to make the write-and-audit a single all-or-nothing unit.
 */
public interface AuditLogApi {

	void record(AuditLogEntry entry);

	/**
	 * The sanctioned, explicit exception to {@link #record(AuditLogEntry)}'s
	 * "resolves {@code tenant_id} from {@code TenantContext} internally"
	 * contract - for an action initiated by an actor with no resolved {@code
	 * TenantContext} at all (Platform Admin; {@code TenantResolutionFilter}
	 * excludes the entire {@code /api/v1/platform-admin/**} path prefix), so
	 * {@link #record(AuditLogEntry)} itself would throw {@code
	 * TenantContextNotResolvedException} if called from that request path.
	 *
	 * <p>{@code tenantId} here is the row's <em>target</em> tenant (e.g. the
	 * tenant a Platform Admin just approved/rejected), never the acting
	 * Platform Admin's own tenant (Platform Admin has none). Mirrors the same
	 * "explicit, reviewed bypass" spirit as {@code
	 * PaymentRepository#findByGatewayReferenceAcrossTenants}'s {@code
	 * AcrossTenants} naming convention (ADR-006), applied here to a write
	 * instead of a read. Callers must resolve {@code tenantId} from a trusted
	 * source (e.g. the target tenant's own id, already validated to exist) -
	 * never from an unvalidated client-supplied value.
	 *
	 * <p>Implementations MUST still participate in the caller's existing
	 * transaction (Spring's default {@code REQUIRED} propagation), per this
	 * interface's own class-level javadoc contract.
	 */
	void recordForTenant(UUID tenantId, AuditLogEntry entry);

}
