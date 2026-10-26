package com.lms.reportinganalytics.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * RPT-02 Student Payment Report run header (spec sections 4 and 8) - exactly
 * what {@code POST /runs} and {@code GET /runs/{runId}} return. The detail
 * rows are stored with it ({@link StudentPaymentRun}) but paged separately.
 * Amounts are 2-dp decimal strings; everything is per currency and never
 * summed across currencies.
 *
 * @param rowCount detail rows of the whole run (all currencies).
 */
public record StudentPaymentReport(UUID runId, Instant expiresAt, Instant generatedAt, String timezone,
		Institute institute, Filters filters, List<Section> sections, Reconciliation reconciliation, int rowCount) {

	public StudentPaymentReport withRun(UUID id, Instant expires) {
		return new StudentPaymentReport(id, expires, generatedAt, timezone, institute, filters, sections,
				reconciliation, rowCount);
	}

	public record Institute(String name) {

	}

	public record CourseRef(UUID id, String name) {

	}

	/**
	 * @param courses the selected courses; empty = all courses of the tenant.
	 * @param paymentMethod null = all methods. The inactive period mode's fields are null.
	 */
	public record Filters(List<CourseRef> courses, String paymentMethod, String periodMode, List<String> months,
			LocalDate startDate, LocalDate endDate, String periodLabel) {

	}

	public record Section(String currency, Summary summary) {

	}

	/**
	 * Section summary over the complete filtered result.
	 *
	 * @param studentsPaid distinct students with at least one confirmation (non-refund) row.
	 * @param receiptCount distinct PAYMENT_CONFIRMED entries with at least one row.
	 * @param refundCount distinct REFUND entries with at least one row.
	 * @param coursePaymentTotal net course payments (refunds subtracted, fees excluded).
	 * @param byMethod per display method; totals sum to {@code coursePaymentTotal},
	 * receipt counts sum to {@code receiptCount}.
	 * @param receivedTotal course payments + service fees + postage (net).
	 */
	public record Summary(long studentsPaid, long receiptCount, long refundCount, long rowCount,
			String coursePaymentTotal, List<MethodTotal> byMethod, String serviceFeeTotal, String postageTotal,
			String receivedTotal) {

	}

	/** {@code method} = {@code ONLINE|BANK_TRANSFER|CASH|CARD|UNKNOWN}; {@code manual} = staff-recorded receipt. */
	public record MethodTotal(String method, boolean manual, String total, long receiptCount) {

	}

	public record Reconciliation(List<CurrencyReconciliation> byCurrency, List<String> notes) {

	}

	/**
	 * {@code grossCoursePayments - refunds} = the section's {@code
	 * coursePaymentTotal}. {@code staffAdjustments}: in-scope manual receipts
	 * recorded with a staff adjustment (amount = expected - received).
	 * {@code excludedNonCash}: FREE / STAFF_GRANTED confirmations (course
	 * amounts). {@code excludedNotMonthAttributable}: MONTHS mode, course-payment
	 * amounts only (fees and postage remain out of this course-payment
	 * reconciliation) of month-less lines of the loaded orders.
	 */
	public record CurrencyReconciliation(String currency, String grossCoursePayments, String refunds,
			CountAmount staffAdjustments, CountAmount excludedNonCash, CountAmount excludedNotMonthAttributable) {

	}

	public record CountAmount(long count, String amount) {

	}

}
