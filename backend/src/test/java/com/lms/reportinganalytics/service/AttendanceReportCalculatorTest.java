package com.lms.reportinganalytics.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.attendancemanagement.api.AttendanceReportFact;
import com.lms.reportinganalytics.service.AttendanceReport.CourseRef;
import com.lms.usermanagement.api.StudentIndexEntry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AttendanceReportCalculatorTest {

	private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");

	private static final AttendanceCriteria CRITERIA = new AttendanceCriteria(UUID.randomUUID(),
			LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 24), "Attendance Report");

	@Test
	void referenceFixtureReconcilesRowsPresentAndExplicitAbsences() {
		List<AttendanceReportFact> facts = new ArrayList<>();
		List<StudentIndexEntry> students = new ArrayList<>();
		for (int student = 1; student <= 45; student++) {
			UUID studentId = UUID.randomUUID();
			students.add(new StudentIndexEntry(studentId, "Student " + student, String.format("%04d", student)));
			for (int day : List.of(3, 7, 10)) {
				facts.add(fact(studentId, day, "PRESENT"));
			}
		}

		AttendanceReport report = calculate(facts, students);

		assertThat(report.rows()).hasSize(45);
		assertThat(report.dates()).containsExactly(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 7),
				LocalDate.of(2026, 10, 10));
		assertThat(report.totals()).isEqualTo(new AttendanceReport.Totals(45, 135, 0));
		assertThat(report.rows().getFirst().indexNumber()).isEqualTo("0001");
	}

	@Test
	void dateMatrixKeepsUnknownSeparateFromExplicitAbsenceAndPresentWinsSameDay() {
		UUID first = UUID.randomUUID();
		UUID duplicateName = UUID.randomUUID();
		List<AttendanceReportFact> facts = List.of(fact(first, 3, "ABSENT"), fact(first, 7, "ABSENT"),
				fact(first, 7, "PRESENT"), fact(duplicateName, 3, "LATE"));
		List<StudentIndexEntry> students = List.of(new StudentIndexEntry(first, "Same Name", "0048"),
				new StudentIndexEntry(duplicateName, "Same Name", "0049"));

		AttendanceReport report = calculate(facts, students);

		assertThat(report.rows()).hasSize(2);
		assertThat(report.rows().get(0).cells()).containsExactly("A", "P");
		assertThat(report.rows().get(1).cells()).containsExactly("P", "—");
		assertThat(report.totals()).isEqualTo(new AttendanceReport.Totals(2, 2, 1));
		assertThat(report.legend().unknown()).contains("No recorded status");
	}

	@Test
	void tenantLocalDateBoundaryUsesTenantZoneAndDeduplicatesSameRecord() {
		UUID student = UUID.randomUUID();
		AttendanceReportFact boundary = new AttendanceReportFact(UUID.randomUUID(), student, "PRESENT", UUID.randomUUID(),
				Instant.parse("2026-10-01T18:30:00Z"));
		AttendanceReport report = calculate(List.of(boundary, boundary), List.of(new StudentIndexEntry(student, "Asha", "0012")));

		assertThat(report.dates()).containsExactly(LocalDate.of(2026, 10, 2));
		assertThat(report.rows()).hasSize(1);
		assertThat(report.totals()).isEqualTo(new AttendanceReport.Totals(1, 1, 0));
	}

	private static AttendanceReport calculate(List<AttendanceReportFact> facts, List<StudentIndexEntry> students) {
		return AttendanceReportCalculator.calculate(new AttendanceReportCalculator.Input(CRITERIA, COLOMBO,
				Instant.parse("2026-10-23T06:21:00Z"), "Kandy Science Academy",
				new CourseRef(CRITERIA.courseId(), "Biology"), facts, students));
	}

	private static AttendanceReportFact fact(UUID studentId, int day, String status) {
		return new AttendanceReportFact(UUID.randomUUID(), studentId, status, UUID.randomUUID(),
				LocalDate.of(2026, 10, day).atTime(9, 0).atZone(COLOMBO).toInstant());
	}

}
