package com.lms.reportinganalytics.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** RPT-03 immutable attendance report header and complete date-matrix dataset. */
public record AttendanceReport(UUID runId, Instant expiresAt, Instant generatedAt, String timezone,
		Institute institute, CourseRef course, String title, Filters filters, List<LocalDate> dates,
		List<Row> rows, Totals totals, Legend legend) {

	public AttendanceReport {
		dates = dates == null ? List.of() : List.copyOf(dates);
		rows = rows == null ? List.of() : List.copyOf(rows);
	}

	public AttendanceReport withRun(UUID id, Instant expires) {
		return new AttendanceReport(id, expires, generatedAt, timezone, institute, course, title, filters, dates, rows,
				totals, legend);
	}

	public record Institute(String name) {

	}

	public record CourseRef(UUID id, String name) {

	}

	public record Filters(UUID courseId, LocalDate startDate, LocalDate endDate, String reportName) {

	}

	public record Row(UUID studentId, String indexNumber, String studentName, List<String> cells) {

		public Row {
			cells = cells == null ? List.of() : List.copyOf(cells);
		}
	}

	public record Totals(int totalRecords, int present, int absent) {

	}

	public record Legend(String present, String absent, String unknown) {

	}

}
