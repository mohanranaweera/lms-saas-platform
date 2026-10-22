package com.lms.reportinganalytics.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * RPT-01 Teacher Income Summary dataset (spec section 4) - the exact shape returned
 * by {@code POST /runs} / {@code GET /runs/{runId}} and the only input of the
 * PDF exports (which never recompute). Amounts are decimal strings with 2
 * decimal places; {@code payins} is always {@code null} ("Not tracked").
 * Every amount is per currency: nothing is ever summed across currencies.
 */
public record TeacherIncomeReport(UUID runId, Instant expiresAt, Instant generatedAt, String timezone,
		Institute institute, Filters filters, List<Section> sections, Reconciliation reconciliation) {

	public TeacherIncomeReport withRun(UUID id, Instant expires) {
		return new TeacherIncomeReport(id, expires, generatedAt, timezone, institute, filters, sections,
				reconciliation);
	}

	public record Institute(String name) {

	}

	/** {@code paymentMethod} null = all methods; the inactive period mode's fields are null. */
	public record Filters(List<TeacherRef> teachers, String paymentMethod, String periodMode, List<String> months,
			LocalDate startDate, LocalDate endDate, String periodLabel) {

	}

	public record TeacherRef(UUID id, String name) {

	}

	public record Section(String currency, Totals totals, List<TeacherBlock> teachers) {

	}

	/**
	 * Section or teacher totals. {@code serviceFees} is signed (refunds reduce
	 * it pro rata). {@code balance} = {@code teacherIncome - payouts}: a period
	 * movement, not an account balance. {@code teacherCount} is 1 on a teacher.
	 */
	public record Totals(String revenue, String teacherIncome, String commission, String serviceFees,
			String payouts, String payins, String balance, long paymentCount, int teacherCount,
			String unratedRevenue) {

	}

	public record TeacherBlock(UUID teacherId, String teacherName, Totals totals, List<CourseRow> courses) {

	}

	/**
	 * {@code teacherIncome}/{@code commission} are {@code null} only if no part
	 * of the row has an effective rate; {@code rateMissing} is {@code true} if
	 * any part has none (that part is in {@code unratedRevenue} of the totals).
	 */
	public record CourseRow(UUID courseId, String courseName, long paymentCount, String revenue,
			String teacherIncome, String commission, boolean rateMissing) {

	}

	/** Per-currency reconciliation (deviation from spec section 4: never summed across currencies). */
	public record Reconciliation(List<CurrencyReconciliation> byCurrency, List<String> notes) {

	}

	/**
	 * {@code grossRevenue - refunds} = the section's {@code totals.revenue}.
	 * {@code excludedNonCash}: FREE / STAFF_GRANTED allocations of the selected
	 * teachers (count = confirmed entries). {@code settledBasisDifference} =
	 * stored settlement item amounts - this report's allocations, for in-scope
	 * allocations already settled. {@code unassignedRevenue}: in-scope revenue
	 * of courses with no teacher (never shown as a row).
	 */
	public record CurrencyReconciliation(String currency, String grossRevenue, String refunds,
			CountAmount excludedNonCash, CountAmount excludedNotMonthAttributable, String settledBasisDifference,
			String unassignedRevenue) {

	}

	public record CountAmount(long count, String amount) {

	}

}
