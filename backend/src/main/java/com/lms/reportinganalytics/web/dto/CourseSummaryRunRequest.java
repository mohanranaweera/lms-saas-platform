package com.lms.reportinganalytics.web.dto;

import java.util.UUID;

/** RPT-05 request; tenant identity and caller are resolved only from authentication. */
public record CourseSummaryRunRequest(UUID teacherId, UUID courseId, String startDate, String endDate, String month,
		String reportName) {

}
