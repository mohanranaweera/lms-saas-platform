package com.lms.auditlogmanagement.api;

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

}
