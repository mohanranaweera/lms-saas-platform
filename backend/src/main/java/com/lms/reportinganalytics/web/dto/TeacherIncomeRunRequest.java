package com.lms.reportinganalytics.web.dto;

import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/v1/reports/teacher-income/runs} body (RPT-01 spec section 4).
 * Enum-like and date fields are plain strings on purpose: every value is
 * validated server-side by {@code TeacherIncomeRequestValidator}, so a bad
 * value is a field-level {@code 422}, never a generic JSON parse {@code 400}.
 * There is deliberately no tenant field - the tenant is always the
 * authenticated caller's.
 *
 * @param paymentMethod {@code GATEWAY|MANUAL_SLIP|MANUAL_RECEIPT}, or null/blank for all.
 * @param periodMode {@code MONTHS|DATES} (required).
 * @param months MONTHS only: 1..36 {@code "YYYY-MM"} values.
 * @param startDate DATES only: ISO {@code yyyy-MM-dd}, inclusive.
 * @param endDate DATES only: ISO {@code yyyy-MM-dd}, inclusive.
 */
public record TeacherIncomeRunRequest(List<UUID> teacherIds, String paymentMethod, String periodMode,
		List<String> months, String startDate, String endDate) {

}
