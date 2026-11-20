package com.lms.reportinganalytics.service;

import java.util.UUID;

/** Stored immutable RPT-03 report run. */
public record AttendanceReportRun(AttendanceReport report) {

	public UUID runId() {
		return report == null ? null : report.runId();
	}

}
