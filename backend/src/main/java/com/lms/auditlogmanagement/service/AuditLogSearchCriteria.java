package com.lms.auditlogmanagement.service;

import java.time.Instant;

/**
 * Internal service-input shape for {@link AuditLogQueryService#search} - not
 * a cross-module contract, so (unlike {@code
 * com.lms.auditlogmanagement.web.dto.AuditLogEntryResponse}) it lives in
 * {@code service}, not {@code api}. All four fields are optional/nullable;
 * an absent filter simply widens the search (see {@code
 * com.lms.auditlogmanagement.repository.AuditLogSpecifications}, whose
 * per-field predicates each return {@code Specification.unrestricted()} for
 * a {@code null} argument).
 *
 * <p>Validated eagerly in this compact constructor - the point at which
 * {@code AuditLogController} builds this record from request params - so an
 * inverted {@code from}/{@code to} range fails fast with a {@code 400}
 * before ever reaching the repository, mirroring {@code
 * attendancemanagement.service.AttendanceReportService}'s equivalent
 * {@code from}-after-{@code to} guard.
 */
public record AuditLogSearchCriteria(Instant from, Instant to, String action, String targetEntity) {

	/**
	 * Matches {@code audit_log.action} and {@code audit_log.target_entity}'s
	 * shared {@code VARCHAR(100)} column length, per {@code
	 * V21__create_payment_slip_schema.sql}'s {@code CREATE TABLE audit_log}.
	 * A filter value longer than the column itself could ever store can only
	 * ever match zero rows, so it is rejected here as invalid input rather
	 * than silently accepted and run as a query.
	 */
	private static final int MAX_FILTER_LENGTH = 100;

	public AuditLogSearchCriteria {
		if (from != null && to != null && from.isAfter(to)) {
			throw new InvalidAuditLogSearchException("'from' must not be after 'to'");
		}
		validateFilter("action", action);
		validateFilter("targetEntity", targetEntity);
	}

	/**
	 * {@code null} (parameter omitted) means "no filter" and is always valid.
	 * A present-but-blank value (e.g. {@code ?action=}) can never match
	 * {@code audit_log}'s own {@code ck_audit_log_action_not_blank}/{@code
	 * ck_audit_log_target_entity_not_blank} CHECK constraints, so it is
	 * rejected here as invalid input rather than silently producing a
	 * zero-row result.
	 */
	private static void validateFilter(String fieldName, String value) {
		if (value == null) {
			return;
		}
		if (value.isBlank()) {
			throw new InvalidAuditLogSearchException("'" + fieldName + "' must not be blank");
		}
		if (value.length() > MAX_FILTER_LENGTH) {
			throw new InvalidAuditLogSearchException(
					"'" + fieldName + "' must not exceed " + MAX_FILTER_LENGTH + " characters");
		}
	}

}
