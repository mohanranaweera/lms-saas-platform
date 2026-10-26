package com.lms.reportinganalytics.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.attendancemanagement.api.AttendanceReportFact;
import com.lms.enrollmentmanagement.api.UnpaidStudentsEnrollmentApi.ParticipationFact;
import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry.ReportCourseAllocation;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry.ReportLineAllocation;
import com.lms.paymentmanagement.api.OrderLineSnapshot;
import com.lms.usermanagement.api.UnpaidStudentProfile;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UnpaidStudentsCalculatorTest {

	private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");
	private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
	private static final UUID COURSE = UUID.randomUUID();

	@Test
	void enrolledStudentWithNoMonthlyOrderIsStillUnpaid() {
		UUID student = UUID.randomUUID();

		UnpaidStudentsCalculator.Result result = calculate(false, List.of(student), List.of(), List.of(), List.of());

		assertThat(result.rows()).singleElement().satisfies(row -> assertThat(row.studentId()).isEqualTo(student));
	}

	@Test
	void includesOnlyEnrolledStudentsWithoutFullCashCoverageAndPreservesIndexes() {
		UUID paid = UUID.randomUUID();
		UUID unpaid = UUID.randomUUID();
		UUID otherCourse = UUID.randomUUID();
		OrderLineSnapshot paidLine = monthlyLine(paid, COURSE, OCTOBER, "1000.00", "0002");
		OrderLineSnapshot unpaidLine = monthlyLine(unpaid, COURSE, OCTOBER, "1000.00", "0007");
		ReportLedgerEntry fullPayment = entry(paidLine, PaymentMethod.GATEWAY, "1000.00", false);
		ReportLedgerEntry wrongCoursePayment = entry(unpaidLine, PaymentMethod.GATEWAY, "1000.00", false, otherCourse);

		UnpaidStudentsCalculator.Result result = calculate(false, List.of(paid, unpaid), List.of(paidLine, unpaidLine),
				List.of(fullPayment, wrongCoursePayment), List.of());

		assertThat(result.report().unpaidCount()).isEqualTo(1);
		assertThat(result.rows()).singleElement().satisfies(row -> {
			assertThat(row.studentId()).isEqualTo(unpaid);
			assertThat(row.indexNumber()).isEqualTo("0007");
		});
	}

	@Test
	void paymentReceiptDateIsIrrelevantButPartialAndReversalRemainUnpaid() {
		UUID beforeMonth = UUID.randomUUID();
		UUID partial = UUID.randomUUID();
		UUID reversed = UUID.randomUUID();
		OrderLineSnapshot beforeLine = monthlyLine(beforeMonth, COURSE, OCTOBER, "1000.00", "0001");
		OrderLineSnapshot partialLine = monthlyLine(partial, COURSE, OCTOBER, "1000.00", "0002");
		OrderLineSnapshot reversedLine = monthlyLine(reversed, COURSE, OCTOBER, "1000.00", "0003");

		UnpaidStudentsCalculator.Result result = calculate(false, List.of(beforeMonth, partial, reversed),
				List.of(beforeLine, partialLine, reversedLine),
				List.of(entry(beforeLine, PaymentMethod.MANUAL_SLIP, "1000.00", false),
						entry(partialLine, PaymentMethod.MANUAL_RECEIPT, "250.00", false),
						entry(reversedLine, PaymentMethod.GATEWAY, "1000.00", false),
						entry(reversedLine, PaymentMethod.GATEWAY, "-1000.00", true)), List.of());

		assertThat(result.rows()).extracting(UnpaidStudentRow::studentId).containsExactly(partial, reversed);
	}

	@Test
	void excludesFreePaymentAndAppliesAttendanceOnlyAfterUnpaidQualification() {
		UUID free = UUID.randomUUID();
		UUID absent = UUID.randomUUID();
		UUID present = UUID.randomUUID();
		OrderLineSnapshot freeLine = monthlyLine(free, COURSE, OCTOBER, "1000.00", "0001");
		OrderLineSnapshot absentLine = monthlyLine(absent, COURSE, OCTOBER, "1000.00", "0002");
		OrderLineSnapshot presentLine = monthlyLine(present, COURSE, OCTOBER, "1000.00", "0003");
		AttendanceReportFact presentFact = new AttendanceReportFact(UUID.randomUUID(), present, "LATE", UUID.randomUUID(),
				Instant.parse("2026-10-10T03:30:00Z"));

		UnpaidStudentsCalculator.Result result = calculate(true, List.of(free, absent, present),
				List.of(freeLine, absentLine, presentLine), List.of(entry(freeLine, PaymentMethod.FREE, "1000.00", false)),
				List.of(presentFact, presentFact));

		assertThat(result.rows()).singleElement().satisfies(row -> {
			assertThat(row.studentId()).isEqualTo(present);
			assertThat(row.attendanceCount()).isEqualTo(1);
		});
	}

	private static UnpaidStudentsCalculator.Result calculate(boolean attended, List<UUID> students, List<OrderLineSnapshot> lines,
			List<ReportLedgerEntry> entries, List<AttendanceReportFact> attendance) {
		List<ParticipationFact> participants = students.stream().map(id -> new ParticipationFact(id, Instant.EPOCH)).toList();
		List<UnpaidStudentProfile> profiles = students.stream().map(id -> new UnpaidStudentProfile(UUID.randomUUID(), id,
				lines.stream().filter(line -> id.equals(line.studentId())).findFirst().map(OrderLineSnapshot::label).orElse(""),
				"Same Name", null, null)).toList();
		return UnpaidStudentsCalculator.calculate(new UnpaidStudentsCalculator.Input(
				new UnpaidStudentsCriteria(COURSE, OCTOBER, attended), COLOMBO, Instant.parse("2026-10-23T08:29:00Z"),
				"Academy", new UnpaidStudentsReport.CourseRef(COURSE, "Chemistry"), participants, lines, entries, attendance,
				profiles));
	}

	private static OrderLineSnapshot monthlyLine(UUID student, UUID course, YearMonth month, String amount, String index) {
		UUID order = UUID.randomUUID();
		return new OrderLineSnapshot(UUID.randomUUID(), order, student, 1, "MONTHLY", course, null, null,
				month.getYear(), month.getMonthValue(), "MONTHLY:" + course + ":" + month, index, new BigDecimal(amount),
				BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(amount), "LKR", false);
	}

	private static ReportLedgerEntry entry(OrderLineSnapshot line, PaymentMethod method, String amount, boolean refund) {
		return entry(line, method, amount, refund, line.courseId());
	}

	private static ReportLedgerEntry entry(OrderLineSnapshot line, PaymentMethod method, String amount, boolean refund,
			UUID allocationCourse) {
		BigDecimal value = new BigDecimal(amount);
		return new ReportLedgerEntry(UUID.randomUUID(), line.orderId(), UUID.randomUUID(), refund, value, Instant.EPOCH,
				null, "LKR", method, true, List.of(new ReportCourseAllocation(allocationCourse, value,
					List.of(new ReportLineAllocation(line.lineId(), 1, "MONTHLY", OCTOBER.getYear(), OCTOBER.getMonthValue(), value)),
					null)));
	}

}
