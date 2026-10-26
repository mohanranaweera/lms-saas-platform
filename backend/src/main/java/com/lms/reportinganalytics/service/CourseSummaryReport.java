package com.lms.reportinganalytics.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Complete immutable RPT-05 snapshot used by preview and PDF export. */
public record CourseSummaryReport(UUID runId, Instant expiresAt, Instant generatedAt, String timezone,
		Institute institute, TeacherRef teacher, CourseRef course, String title, Filters filters, Kpis kpis,
		List<WeeklyRow> weekly, List<CumulativeRow> cumulative, List<String> notes) {

	public CourseSummaryReport {
		weekly = weekly == null ? List.of() : List.copyOf(weekly);
		cumulative = cumulative == null ? List.of() : List.copyOf(cumulative);
		notes = notes == null ? List.of() : List.copyOf(notes);
	}

	public CourseSummaryReport withRun(UUID id, Instant expires) {
		return new CourseSummaryReport(id, expires, generatedAt, timezone, institute, teacher, course, title, filters,
				kpis, weekly, cumulative, notes);
	}

	public record Institute(String name, String currency) {

	}

	public record TeacherRef(UUID id, String name) {

	}

	public record CourseRef(UUID id, String name) {

	}

	public record Filters(UUID teacherId, UUID courseId, LocalDate startDate, LocalDate endDate, String month,
			String reportName) {

	}

	public record Kpis(int totalStudents, int newRegistrations, int payments, String commissionPercent,
			int classes, int attendance, String revenue, String commission, String teacherShare) {

	}

	public record WeeklyRow(int week, String days, int newRegistrations, int classes, int attendance, int payments,
			String revenue, String commission, String teacherShare) {

	}

	public record CumulativeRow(String category, int count, String unitPrice, String grossListValue, String commission,
			String teacherShare, boolean tracked) {

	}

	public static String money(BigDecimal amount) {
		return amount == null ? null : amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
	}

}
