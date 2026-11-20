package com.lms.attendancemanagement.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Narrow read contract for reporting. Tenant identity and authorization remain
 * inside attendance-management; callers never provide a tenant id.
 */
public interface AttendanceReportQueryApi {

	List<AttendanceReportFact> findReportFacts(UUID courseId, Instant fromInclusive, Instant toExclusive, int maxFacts);

	/**
	 * Financial-report projection over the same canonical attendance facts. The
	 * attendance module enforces the caller's FINANCE_EXPENSES/VIEW grant; tenant
	 * scope remains resolved context, never a caller-supplied tenant id.
	 */
	List<AttendanceReportFact> findReportFactsForFinancialReport(UUID courseId, Instant fromInclusive,
			Instant toExclusive, int maxFacts);

}
