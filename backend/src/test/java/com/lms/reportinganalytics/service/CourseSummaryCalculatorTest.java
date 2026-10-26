package com.lms.reportinganalytics.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.attendancemanagement.api.AttendanceReportFact;
import com.lms.enrollmentmanagement.api.CourseSummaryEnrollmentReportApi.FreeCardFact;
import com.lms.enrollmentmanagement.api.CourseSummaryEnrollmentReportApi.ParticipationFact;
import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry.ReportCourseAllocation;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry.ReportLineAllocation;
import com.lms.ledgersettlementmanagement.api.TeacherIncomeLedgerApi.ShareRate;
import com.lms.liveclassmanagement.api.CourseSessionReportApi.SessionFact;
import com.lms.paymentmanagement.api.OrderLineSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CourseSummaryCalculatorTest {

	private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");

	@Test
	void confirmedPaymentReconcilesWeeklyAndDiscountedCumulativeCount() {
		UUID teacher = UUID.randomUUID(), course = UUID.randomUUID(), student = UUID.randomUUID(), order = UUID.randomUUID();
		UUID line = UUID.randomUUID();
		CourseSummaryCriteria criteria = criteria(teacher, course);
		Instant oct3 = Instant.parse("2026-10-03T03:30:00Z");
		OrderLineSnapshot lineSnapshot = new OrderLineSnapshot(line, order, student, 1, "MONTHLY", course, null, null,
				2026, 10, "MONTHLY:" + course + ":2026-10", "October", new BigDecimal("4000.00"),
				BigDecimal.ZERO, new BigDecimal("1000.00"), new BigDecimal("3000.00"), "LKR", false);
		ReportLedgerEntry entry = entry(UUID.randomUUID(), order, course, line, oct3, false, "3000.00", true);
		CourseSummaryReport report = calculate(criteria, student, order, List.of(entry), List.of(lineSnapshot),
				List.of(new ParticipationFact(UUID.randomUUID(), student, oct3, "SELF_ENROLL")),
				List.of(new FreeCardFact(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 10, 1),
						LocalDate.of(2026, 10, 31), "LKR", new BigDecimal("4000.00"), new BigDecimal("4000.00"), false)),
				List.of(new SessionFact(UUID.randomUUID(), oct3, oct3.plusSeconds(3600), "COMPLETED")),
				List.of(new AttendanceReportFact(UUID.randomUUID(), student, "PRESENT", UUID.randomUUID(), oct3)));

		assertThat(report.kpis().totalStudents()).isEqualTo(1);
		assertThat(report.kpis().payments()).isEqualTo(1);
		assertThat(report.kpis().revenue()).isEqualTo("3000.00");
		assertThat(report.kpis().commission()).isEqualTo("750.00");
		assertThat(report.kpis().teacherShare()).isEqualTo("2250.00");
		assertThat(report.kpis().commissionPercent()).isEqualTo("25.00%");
		assertThat(report.cumulative()).extracting(CourseSummaryReport.CumulativeRow::category)
			.containsExactly("Full Cards", "Discounted Cards", "Free Card Students", "Not Paid Full Cards");
		assertThat(report.cumulative().get(0).count()).isZero();
		assertThat(report.cumulative().get(1).count()).isEqualTo(1);
		assertThat(weeklyPayments(report)).isEqualTo(paidCardCount(report)).isEqualTo(1);
		assertThat(report.cumulative().get(2).teacherShare()).isEqualTo("—");
		assertThat(report.cumulative().get(3).tracked()).isFalse();
	}

	@Test
	void legacyLineLessPaymentIsAFullCardAndReconciles() {
		UUID teacher = UUID.randomUUID(), course = UUID.randomUUID(), student = UUID.randomUUID(), order = UUID.randomUUID();
		Instant date = Instant.parse("2026-10-03T03:30:00Z");
		ReportLedgerEntry entry = entry(UUID.randomUUID(), order, course, null, date, false, "4000.00", false);

		CourseSummaryReport report = calculate(criteria(teacher, course), student, order, List.of(entry), List.of(),
				List.of(), List.of(), List.of(), List.of());

		assertThat(report.kpis().payments()).isEqualTo(1);
		assertThat(report.cumulative().get(0).count()).isEqualTo(1);
		assertThat(report.cumulative().get(0).unitPrice()).isEqualTo("4000.00");
		assertThat(report.cumulative().get(1).count()).isZero();
		assertThat(weeklyPayments(report)).isEqualTo(paidCardCount(report)).isEqualTo(1);
	}

	@Test
	void refundAndDuplicateEntryDoNotCreatePaidCardCounts() {
		UUID teacher = UUID.randomUUID(), course = UUID.randomUUID(), student = UUID.randomUUID(), order = UUID.randomUUID();
		UUID entryId = UUID.randomUUID();
		Instant date = Instant.parse("2026-10-03T03:30:00Z");
		ReportLedgerEntry refund = entry(entryId, order, course, null, date, true, "-3000.00", false);
		CourseSummaryReport report = calculate(criteria(teacher, course), student, order, List.of(refund, refund),
				List.of(), List.of(), List.of(), List.of(), List.of());

		assertThat(report.kpis().payments()).isZero();
		assertThat(weeklyPayments(report)).isZero();
		assertThat(report.kpis().revenue()).isEqualTo("-3000.00");
		assertThat(paidCardCount(report)).isZero();
	}

	private static CourseSummaryCriteria criteria(UUID teacher, UUID course) {
		return new CourseSummaryCriteria(teacher, course, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
				YearMonth.of(2026, 10), "Teacher Report");
	}

	private static ReportLedgerEntry entry(UUID entryId, UUID orderId, UUID courseId, UUID lineId, Instant at,
			boolean refund, String amount, boolean lineOrder) {
		BigDecimal value = new BigDecimal(amount);
		List<ReportLineAllocation> lines = lineId == null ? List.of()
				: List.of(new ReportLineAllocation(lineId, 1, "MONTHLY", 2026, 10, value));
		return new ReportLedgerEntry(entryId, orderId, UUID.randomUUID(), refund, value, at,
				refund ? UUID.randomUUID() : null, "LKR", PaymentMethod.GATEWAY, lineOrder,
				List.of(new ReportCourseAllocation(courseId, value, lines, null)));
	}

	private static CourseSummaryReport calculate(CourseSummaryCriteria criteria, UUID student, UUID order,
			List<ReportLedgerEntry> entries, List<OrderLineSnapshot> lines, List<ParticipationFact> participation,
			List<FreeCardFact> freeCards, List<SessionFact> sessions, List<AttendanceReportFact> attendance) {
		return CourseSummaryCalculator.calculate(new CourseSummaryCalculator.Input(criteria, COLOMBO,
				Instant.parse("2026-10-24T06:00:00Z"), "Institute", "LKR",
				new CourseSummaryReport.TeacherRef(criteria.teacherId(), "Teacher"),
				new CourseSummaryReport.CourseRef(criteria.courseId(), "Course"), participation, freeCards, sessions,
				attendance, entries, Map.of(order, student), lines,
				List.of(new ShareRate(criteria.teacherId(), new BigDecimal("75.00"), LocalDate.of(2026, 1, 1)))));
	}

	private static int weeklyPayments(CourseSummaryReport report) {
		return report.weekly().stream().mapToInt(CourseSummaryReport.WeeklyRow::payments).sum();
	}

	private static int paidCardCount(CourseSummaryReport report) {
		return report.cumulative().get(0).count() + report.cumulative().get(1).count();
	}

}
