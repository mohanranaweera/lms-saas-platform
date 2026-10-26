package com.lms.reportinganalytics.service;

import java.time.LocalDate;
import java.util.UUID;

/** Validated RPT-03 filter. */
public record AttendanceCriteria(UUID courseId, LocalDate startDate, LocalDate endDate, String reportName) {

}
