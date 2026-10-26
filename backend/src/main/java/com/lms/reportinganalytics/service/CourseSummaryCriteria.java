package com.lms.reportinganalytics.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/** Validated RPT-05 parameters. */
public record CourseSummaryCriteria(UUID teacherId, UUID courseId, LocalDate startDate, LocalDate endDate,
		YearMonth month, String reportName) {

}
