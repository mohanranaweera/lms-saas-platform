package com.lms.reportinganalytics.service;

import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

/** Immutable Unpaid Students Report run header. Rows remain in the owner-bound snapshot. */
public record UnpaidStudentsReport(UUID runId, Instant expiresAt, Instant generatedAt, String timezone,
		Institute institute, CourseRef course, Filters filters, EnrollmentBasis enrollmentBasis, int unpaidCount) {

	public UnpaidStudentsReport withRun(UUID id, Instant expires) {
		return new UnpaidStudentsReport(id, expires, generatedAt, timezone, institute, course, filters, enrollmentBasis,
				unpaidCount);
	}

	public record Institute(String name) {
	}

	public record CourseRef(UUID id, String name) {
	}

	public record Filters(YearMonth billingMonth, String billingMonthLabel, boolean atLeastOneAttended) {
	}

	/** Current append-only course participation as observed at report generation. */
	public record EnrollmentBasis(String type, Instant asOf) {
	}

}
