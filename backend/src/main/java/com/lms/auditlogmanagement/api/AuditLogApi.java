package com.lms.auditlogmanagement.api;

/**
 * The only contract other domains may depend on for durable audit logging
 * (MVP-011 §21 item 2, option (A) - product owner approved). Deliberately
 * minimal: a single write method, no read/query method - this module does
 * not build a query UI or consume other domains' pending events (e.g.
 * {@code CoursePriceChangedEvent}) at this scope.
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
