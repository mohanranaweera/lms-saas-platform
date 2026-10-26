package com.lms.reportinganalytics.web.dto;

import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/v1/reports/student-payments/runs} body (RPT-02 spec section
 * 2). Enum-like and date fields are plain strings on purpose: every value is
 * validated server-side by {@code StudentPaymentRequestValidator}, so a bad
 * value is a field-level {@code 422}, never a generic JSON parse {@code 400}.
 * There is deliberately no tenant field - the tenant is always the
 * authenticated caller's.
 *
 * @param courseIds 0..50 course ids of the caller's tenant; empty / absent = all courses.
 * @param paymentMethod {@code GATEWAY|MANUAL_SLIP|MANUAL_RECEIPT}, or null/blank for all.
 * @param periodMode {@code MONTHS|DATES} (required).
 * @param months MONTHS only: 1..36 {@code "YYYY-MM"} values.
 * @param startDate DATES only: ISO {@code yyyy-MM-dd}, inclusive.
 * @param endDate DATES only: ISO {@code yyyy-MM-dd}, inclusive (= startDate for a single day).
 */
public record StudentPaymentRunRequest(List<UUID> courseIds, String paymentMethod, String periodMode,
		List<String> months, String startDate, String endDate) {

}
