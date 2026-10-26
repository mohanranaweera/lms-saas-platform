package com.lms.reportinganalytics.service;

import com.lms.attendancemanagement.api.AttendanceReportFact;
import com.lms.reportinganalytics.service.AttendanceReport.CourseRef;
import com.lms.reportinganalytics.service.AttendanceReport.Filters;
import com.lms.reportinganalytics.service.AttendanceReport.Institute;
import com.lms.reportinganalytics.service.AttendanceReport.Legend;
import com.lms.reportinganalytics.service.AttendanceReport.Row;
import com.lms.reportinganalytics.service.AttendanceReport.Totals;
import com.lms.usermanagement.api.StudentIndexEntry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Pure RPT-03 date-matrix aggregation; no persistence or authorization. */
public final class AttendanceReportCalculator {

	public static final String PRESENT = "P";

	public static final String ABSENT = "A";

	public static final String UNKNOWN = "—";

	public record Input(AttendanceCriteria criteria, ZoneId zone, Instant generatedAt, String instituteName,
			CourseRef course, List<AttendanceReportFact> facts, List<StudentIndexEntry> students) {

	}

	private AttendanceReportCalculator() {
	}

	public static AttendanceReport calculate(Input input) {
		Map<UUID, StudentIndexEntry> students = new HashMap<>();
		input.students().forEach(student -> students.put(student.userId(), student));
		Set<UUID> seenRecords = new LinkedHashSet<>();
		Map<UUID, Map<LocalDate, List<String>>> statuses = new LinkedHashMap<>();
		TreeSet<LocalDate> dates = new TreeSet<>();
		for (AttendanceReportFact fact : input.facts()) {
			if (fact == null || fact.recordId() == null || !seenRecords.add(fact.recordId()) || fact.studentId() == null
					|| fact.occurredAt() == null) {
				continue;
			}
			LocalDate date = fact.occurredAt().atZone(input.zone()).toLocalDate();
			if (date.isBefore(input.criteria().startDate()) || date.isAfter(input.criteria().endDate())) {
				continue;
			}
			dates.add(date);
			statuses.computeIfAbsent(fact.studentId(), ignored -> new LinkedHashMap<>())
				.computeIfAbsent(date, ignored -> new ArrayList<>())
				.add(fact.status());
		}

		List<LocalDate> orderedDates = List.copyOf(dates);
		List<Row> rows = statuses.keySet().stream().map(studentId -> row(studentId, statuses.get(studentId), orderedDates,
				students.get(studentId))).sorted(ROW_ORDER).toList();
		int present = 0;
		int absent = 0;
		for (Row row : rows) {
			for (String cell : row.cells()) {
				if (PRESENT.equals(cell)) {
					present++;
				}
				else if (ABSENT.equals(cell)) {
					absent++;
				}
			}
		}
		AttendanceCriteria criteria = input.criteria();
		return new AttendanceReport(null, null, input.generatedAt(), input.zone().getId(),
				new Institute(input.instituteName()), input.course(), criteria.reportName(),
				new Filters(criteria.courseId(), criteria.startDate(), criteria.endDate(), criteria.reportName()),
				orderedDates, rows, new Totals(rows.size(), present, absent),
				new Legend("P = Present (including Late)", "A = Explicitly absent", "— = No recorded status"));
	}

	private static Row row(UUID studentId, Map<LocalDate, List<String>> byDate, List<LocalDate> dates,
			StudentIndexEntry student) {
		String index = student == null || student.indexNumber() == null ? "" : student.indexNumber();
		String name = student == null || student.name() == null || student.name().isBlank() ? "Unknown student"
				: student.name().trim();
		List<String> cells = dates.stream().map(date -> marker(byDate.get(date))).toList();
		return new Row(studentId, index, name, cells);
	}

	static String marker(List<String> values) {
		if (values == null || values.isEmpty()) {
			return UNKNOWN;
		}
		boolean allAbsent = true;
		for (String value : values) {
			String status = value == null ? "" : value.toUpperCase(Locale.ROOT);
			if ("PRESENT".equals(status) || "LATE".equals(status)) {
				return PRESENT;
			}
			if (!"ABSENT".equals(status)) {
				allAbsent = false;
			}
		}
		return allAbsent ? ABSENT : UNKNOWN;
	}

	private static final Comparator<Row> ROW_ORDER = Comparator
		.comparing(Row::indexNumber, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
		.thenComparing(Row::studentName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
		.thenComparing(Row::studentId);

}
