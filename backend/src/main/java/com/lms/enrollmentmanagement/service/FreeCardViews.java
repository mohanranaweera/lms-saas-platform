package com.lms.enrollmentmanagement.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** ADR-030 Free Card service commands and views (plan §4) - never JPA entities. */
public final class FreeCardViews {

	private FreeCardViews() {
	}

	/** {@code studentId} is a student_profile id (resolved server-side). */
	public record IssueCommand(UUID studentId, UUID courseId, String cardType, LocalDate validFrom, LocalDate validTo,
			String reason, UUID idempotencyKey) {

	}

	/**
	 * {@code studentId} = the student_profile id (as addressed by the staff
	 * UI; {@code null} if the profile no longer resolves); {@code coveredMonths}
	 * as {@code yyyy-MM}; {@code status} SCHEDULED | ACTIVE | EXPIRED | REVOKED
	 * at read time; staff names are best-effort (tenant_user email).
	 */
	public record FreeCardView(UUID id, UUID studentId, String studentName, UUID courseId, String courseTitle,
			String cardType, LocalDate validFrom, LocalDate validTo, String timezone, Instant effectiveFrom,
			Instant effectiveUntil, List<String> coveredMonths, String status, String reason, String currency,
			BigDecimal nominalMonthlyAmount, BigDecimal nominalValue, UUID issuedBy, String issuedByName,
			Instant issuedAt, Instant revokedAt, UUID revokedBy, String revokedByName, String revokedReason,
			boolean enrollmentCreated, boolean replay) {

	}

}
