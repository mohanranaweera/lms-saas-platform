package com.lms.reportinganalytics.web.dto;

import java.util.UUID;

/**
 * RPT-03 request. Values stay plain strings so server validation returns stable
 * field-level 422 responses; the tenant is always authenticated context.
 */
public record AttendanceRunRequest(UUID courseId, String startDate, String endDate, String reportName) {

}
