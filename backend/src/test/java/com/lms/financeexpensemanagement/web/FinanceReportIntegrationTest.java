package com.lms.financeexpensemanagement.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.financeexpensemanagement.FinanceTestSupport;
import com.lms.financeexpensemanagement.domain.ExpenseMethod;
import com.lms.financeexpensemanagement.service.ExpenseCategoryService.CategoryView;
import com.lms.financeexpensemanagement.service.ExpenseService.ExpenseView;
import com.lms.financeexpensemanagement.service.FinanceReportService.CourseRevenueReport;
import com.lms.financeexpensemanagement.service.FinanceReportService.CourseRevenueRow;
import com.lms.financeexpensemanagement.service.FinanceReportService.FinanceSummary;
import com.lms.financeexpensemanagement.service.FinanceReportService.PeriodReport;
import com.lms.financeexpensemanagement.service.FinanceReportService.PeriodRow;
import com.lms.financeexpensemanagement.service.FinanceReportService.TeacherRevenueReport;
import com.lms.identityaccessservice.HttpResult;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Wave 7 Phase D - report correctness against a hand-computed fixture
 * (tenant timezone = default UTC):
 *
 * <pre>
 * Course X (teacher1, 100.00): p1 2025-03-05 +100; p2 2025-03-20 +100, refund 30.00 recorded 2025-04-02
 * Course Y (teacher1,  40.00): p3 2025-03-31T23:30Z +40   (last-minute-of-March boundary)
 * Course Z (teacher2, 250.00): p4 2025-04-10 +250; p5 2025-05-01T00:00Z +250 (OUT of range - exclusive end)
 * Expenses: Rent 2025-03-15 500.00 CASH; Food 2025-04-03 60.25 CARD; Rent 2025-04-04 1000.00 VOIDED
 * Range 2025-03-01..2025-04-30:
 *   income gross 490.00, refunds 30.00, net 460.00 (4 payments, 1 refund)
 *   expenses 560.25 (2) -> net result -100.25
 *   courses: Z 250 | X 200-30=170 | Y 40 ; teachers: teacher2 250 | teacher1 210
 *   periods: 2025-03 240/0/240, exp 500.00, net -260.00 ; 2025-04 250/30/220, exp 60.25, net 159.75
 * </pre>
 */
@Tag("cross-tenant")
class FinanceReportIntegrationTest extends FinanceTestSupport {

	private static final String RANGE = "from=2025-03-01&to=2025-04-30";

	@Test
	void reportsMatchHandComputedLedgerAndExpenseFixture() {
		Fixture f = seedFixture("rep-main");
		FinanceTenant t = f.tenant();

		HttpResult<FinanceSummary> summary = getReport(t.host(), t.financeToken(), "summary", RANGE,
				FinanceSummary.class);
		assertThat(summary.getStatusCode()).isEqualTo(HttpStatus.OK);
		FinanceSummary s = summary.getBody().data();
		assertThat(s.timezone()).isEqualTo("UTC");
		assertThat(s.income().gross()).isEqualByComparingTo("490.00");
		assertThat(s.income().refunds()).isEqualByComparingTo("30.00");
		assertThat(s.income().net()).isEqualByComparingTo("460.00");
		assertThat(s.income().paymentCount()).isEqualTo(4);
		assertThat(s.income().refundCount()).isEqualTo(1);
		assertThat(s.expenses().total()).isEqualByComparingTo("560.25");
		assertThat(s.expenses().count()).isEqualTo(2);
		assertThat(s.expenses().byCategory()).hasSize(2);
		assertThat(s.expenses().byCategory().get(0).categoryName()).isEqualTo("Rent");
		assertThat(s.expenses().byCategory().get(0).total()).isEqualByComparingTo("500.00");
		assertThat(s.expenses().byMethod()).anySatisfy(m -> {
			assertThat(m.method()).isEqualTo(ExpenseMethod.CARD);
			assertThat(m.total()).isEqualByComparingTo("60.25");
		});
		assertThat(s.netResult()).isEqualByComparingTo("-100.25");

		CourseRevenueReport courses = getReport(t.host(), t.financeToken(), "course-revenue", RANGE,
				CourseRevenueReport.class)
			.getBody()
			.data();
		assertThat(courses.rows()).extracting(CourseRevenueRow::courseId)
			.containsExactly(f.courseZ().id(), f.courseX().id(), f.courseY().id());
		CourseRevenueRow x = courses.rows().get(1);
		assertThat(x.gross()).isEqualByComparingTo("200.00");
		assertThat(x.refunds()).isEqualByComparingTo("30.00");
		assertThat(x.net()).isEqualByComparingTo("170.00");
		assertThat(x.paymentCount()).isEqualTo(2);
		assertThat(x.refundCount()).isEqualTo(1);
		assertThat(x.teacherId()).isEqualTo(t.teacher().getId());
		assertThat(x.courseTitle()).isEqualTo(f.courseX().name());
		assertThat(courses.totals().net()).isEqualByComparingTo("460.00");

		TeacherRevenueReport teachers = getReport(t.host(), t.financeToken(), "teacher-revenue", RANGE,
				TeacherRevenueReport.class)
			.getBody()
			.data();
		assertThat(teachers.rows()).hasSize(2);
		assertThat(teachers.rows().get(0).teacherId()).isEqualTo(t.teacher2().getId());
		assertThat(teachers.rows().get(0).net()).isEqualByComparingTo("250.00");
		assertThat(teachers.rows().get(1).teacherId()).isEqualTo(t.teacher().getId());
		assertThat(teachers.rows().get(1).teacherEmail()).isEqualTo("teacher@example.test");
		assertThat(teachers.rows().get(1).courseCount()).isEqualTo(2);
		assertThat(teachers.rows().get(1).gross()).isEqualByComparingTo("240.00");
		assertThat(teachers.rows().get(1).net()).isEqualByComparingTo("210.00");

		PeriodReport periods = getReport(t.host(), t.financeToken(), "periods", RANGE, PeriodReport.class)
			.getBody()
			.data();
		assertThat(periods.rows()).extracting(PeriodRow::period).containsExactly("2025-03", "2025-04");
		PeriodRow march = periods.rows().get(0);
		assertThat(march.incomeGross()).isEqualByComparingTo("240.00");
		assertThat(march.refunds()).isEqualByComparingTo("0.00");
		assertThat(march.expenses()).isEqualByComparingTo("500.00");
		assertThat(march.netResult()).isEqualByComparingTo("-260.00");
		PeriodRow april = periods.rows().get(1);
		assertThat(april.incomeGross()).isEqualByComparingTo("250.00");
		assertThat(april.refunds()).isEqualByComparingTo("30.00");
		assertThat(april.incomeNet()).isEqualByComparingTo("220.00");
		assertThat(april.expenses()).isEqualByComparingTo("60.25");
		assertThat(april.netResult()).isEqualByComparingTo("159.75");
		assertThat(april.to()).isEqualTo(LocalDate.of(2025, 4, 30));

		// Widening the range to May picks up the boundary payment p5.
		FinanceSummary withMay = getReport(t.host(), t.financeToken(), "summary", "from=2025-03-01&to=2025-05-31",
				FinanceSummary.class)
			.getBody()
			.data();
		assertThat(withMay.income().gross()).isEqualByComparingTo("740.00");
	}

	@Test
	void reportsNeverIncludeAnotherTenantsFigures() {
		Fixture a = seedFixture("rep-xt-a");
		FinanceTenant b = seedFinanceTenant("rep-xt-b");
		CourseResponse courseB = createPricedCourse(b, "rep-xt-b-course", b.teacher().getId(), "999.00");
		UUID paymentB = confirmedPayment(b, courseB.id());
		backdateConfirmedEntry(paymentB, utc("2025-03-10T10:00:00Z"));
		CategoryView categoryB = createCategoryOrFail(b.host(), b.financeToken(), "Rent");
		createExpenseOrFail(b.host(), b.financeToken(), categoryB.id(), LocalDate.of(2025, 3, 11), "777.00");

		FinanceSummary summaryA = getReport(a.tenant().host(), a.tenant().financeToken(), "summary", RANGE,
				FinanceSummary.class)
			.getBody()
			.data();
		assertThat(summaryA.income().gross()).isEqualByComparingTo("490.00");
		assertThat(summaryA.expenses().total()).isEqualByComparingTo("560.25");
		assertThat(getReport(a.tenant().host(), a.tenant().financeToken(), "course-revenue", RANGE,
				CourseRevenueReport.class)
			.getBody()
			.data()
			.rows()).extracting(CourseRevenueRow::courseId).doesNotContain(courseB.id());

		FinanceSummary summaryB = getReport(b.host(), b.financeToken(), "summary", RANGE, FinanceSummary.class)
			.getBody()
			.data();
		assertThat(summaryB.income().gross()).isEqualByComparingTo("999.00");
		assertThat(summaryB.expenses().total()).isEqualByComparingTo("777.00");
	}

	@Test
	void reportPermissionsAndRangeValidation() {
		FinanceTenant t = seedFinanceTenant("rep-perm");
		for (String path : List.of("summary", "course-revenue", "teacher-revenue", "periods")) {
			assertThat(getReport(t.host(), t.auditorToken(), path, RANGE, Object.class).getStatusCode())
				.isEqualTo(HttpStatus.OK);
			assertThat(getReport(t.host(), t.adminToken(), path, RANGE, Object.class).getStatusCode())
				.isEqualTo(HttpStatus.OK);
			assertThat(getReport(t.host(), t.studentToken(), path, RANGE, Object.class).getStatusCode())
				.isEqualTo(HttpStatus.FORBIDDEN);
			assertThat(getReport(t.host(), t.teacherToken(), path, RANGE, Object.class).getStatusCode())
				.isEqualTo(HttpStatus.FORBIDDEN);
			assertThat(getReport(t.host(), t.financeToken(), path, "from=2025-05-01&to=2025-04-01", Object.class)
				.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(getReport(t.host(), t.financeToken(), path, "from=2020-01-01&to=2025-01-01", Object.class)
				.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}
		// Defaults to the current month when no range is given.
		FinanceSummary empty = getReport(t.host(), t.financeToken(), "summary", "", FinanceSummary.class)
			.getBody()
			.data();
		assertThat(empty.from()).isEqualTo(LocalDate.now(java.time.ZoneOffset.UTC).withDayOfMonth(1));
		assertThat(empty.income().gross()).isEqualByComparingTo("0.00");
	}

	private Fixture seedFixture(String prefix) {
		FinanceTenant t = seedFinanceTenant(prefix);
		CourseResponse courseX = createPricedCourse(t, prefix + "-x", t.teacher().getId(), "100.00");
		CourseResponse courseY = createPricedCourse(t, prefix + "-y", t.teacher().getId(), "40.00");
		CourseResponse courseZ = createPricedCourse(t, prefix + "-z", t.teacher2().getId(), "250.00");

		backdateConfirmedEntry(confirmedPayment(t, courseX.id()), utc("2025-03-05T10:00:00Z"));
		UUID p2 = confirmedPayment(t, courseX.id());
		backdateConfirmedEntry(p2, utc("2025-03-20T10:00:00Z"));
		refund(t, p2, "30.00");
		backdateRefundEntry(p2, utc("2025-04-02T09:00:00Z"));
		backdateConfirmedEntry(confirmedPayment(t, courseY.id()), utc("2025-03-31T23:30:00Z"));
		backdateConfirmedEntry(confirmedPayment(t, courseZ.id()), utc("2025-04-10T10:00:00Z"));
		backdateConfirmedEntry(confirmedPayment(t, courseZ.id()), utc("2025-05-01T00:00:00Z"));

		CategoryView rent = createCategoryOrFail(t.host(), t.financeToken(), "Rent");
		CategoryView food = createCategoryOrFail(t.host(), t.financeToken(), "Food");
		createExpenseOrFail(t.host(), t.financeToken(), rent.id(), LocalDate.of(2025, 3, 15), "500.00");
		createExpense(t.host(), t.financeToken(), food.id(), LocalDate.of(2025, 4, 3), "60.25", "CARD", null);
		ExpenseView voided = createExpenseOrFail(t.host(), t.financeToken(), rent.id(), LocalDate.of(2025, 4, 4),
				"1000.00");
		voidExpense(t.host(), t.financeToken(), voided.id(), "Entered twice");
		return new Fixture(t, courseX, courseY, courseZ);
	}

	private record Fixture(FinanceTenant tenant, CourseResponse courseX, CourseResponse courseY,
			CourseResponse courseZ) {
	}

}
