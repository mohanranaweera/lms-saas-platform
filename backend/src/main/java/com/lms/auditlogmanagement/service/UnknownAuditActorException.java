package com.lms.auditlogmanagement.service;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * Thrown by {@link AuditLogService#requireKnownActor(java.util.UUID)} when an
 * actor id passed to {@link AuditLogService#record} /
 * {@link AuditLogService#recordForTenant} does not resolve to a known
 * {@code tenant_user} or {@code platform_admin_user} row.
 *
 * <p>{@code audit_log.fk_audit_log_actor} was dropped in {@code
 * V32__relax_audit_log_actor_fk_for_platform_admin_actors.sql} (actor_id
 * became a polymorphic reference once a second actor table -
 * {@code platform_admin_user} - existed alongside {@code tenant_user}), so
 * actor-existence is now enforced in {@code requireKnownActor} rather than by
 * a schema-level FK. Reaching this guard's failure branch means an
 * authenticated caller's id doesn't back a real row - a genuine
 * internal-integrity inconsistency, not a client input error - so this stays
 * a {@code 5xx}. It is nonetheless given its own type/error code (rather than
 * throwing a generic {@link IllegalArgumentException} that falls into {@code
 * GlobalExceptionHandler}'s catch-all {@code handleUnexpected}) purely so it
 * is distinguishable from an arbitrary unhandled bug in logs, metrics, and by
 * API consumers - a Low/observability fix, not a redesign.
 *
 * <p>Deliberately extends {@link ApplicationException} rather than requiring
 * a dedicated {@code @ExceptionHandler} in {@code GlobalExceptionHandler}:
 * that class already maps any {@code ApplicationException} subclass
 * generically via {@code httpStatus}/{@code errorCode}, specifically so it
 * never needs to import a business/domain module's exception types (see
 * {@code ApplicationException}'s javadoc, and {@code
 * .claude/rules/architecture.md}'s rule that {@code com.lms.common} must
 * never depend back on a business/domain module).
 *
 * <p>{@code message} is client-safe by construction (mirrors {@code
 * ServiceUnavailableException}'s convention) - it never includes the actor
 * id itself, since that is returned to the client verbatim. The offending
 * actor id is logged server-side by {@link AuditLogService#requireKnownActor}
 * before this is thrown.
 */
public class UnknownAuditActorException extends ApplicationException {

	public UnknownAuditActorException() {
		super(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCodes.UNKNOWN_AUDIT_ACTOR,
				"Unable to record audit log entry: the acting identity could not be verified against a known user record");
	}

}
