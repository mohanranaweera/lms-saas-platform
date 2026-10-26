package com.lms.reportinganalytics.service;

import static com.lms.reportinganalytics.service.StudentPaymentFixtures.dates;
import static com.lms.reportinganalytics.service.StudentPaymentFixtures.months;
import static com.lms.reportinganalytics.service.StudentPaymentFixtures.sum;
import static org.assertj.core.api.Assertions.assertThat;

import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry;
import com.lms.reportinganalytics.service.StudentPaymentFixtures.Book;
import com.lms.reportinganalytics.service.StudentPaymentFixtures.Order;
import com.lms.reportinganalytics.service.StudentPaymentReport.CurrencyReconciliation;
import com.lms.reportinganalytics.service.StudentPaymentReport.MethodTotal;
import com.lms.reportinganalytics.service.StudentPaymentReport.Summary;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * RPT-02 calculator rules (spec docs/specs/student-payment-report.md sections
 * 3, 3-R, 4), no database. Acceptance ids from the task are in the test names.
 * Tenant timezone Asia/Colombo (UTC+05:30).
 */
class StudentPaymentCalculatorTest {

	private static Summary only(StudentPaymentCalculator.Result result) {
		assertThat(result.report().sections()).hasSize(1);
		return result.report().sections().get(0).summary();
	}

	private static CurrencyReconciliation recon(StudentPaymentCalculator.Result result) {
		return result.report().reconciliation().byCurrency().get(0);
	}

	/** A: 5 students x 4,000 + 100 fee; 3 approved slips, 2 gateway payments, all on 08 Oct 2026 local. */
	static Book referenceFixture() {
		Book book = new Book();
		UUID course = book.course("Combined Mathematics 2026");
		String[] times = { "2026-10-08T03:00:00Z", "2026-10-08T04:10:00Z", "2026-10-08T05:20:00Z",
				"2026-10-08T09:30:00Z", "2026-10-08T12:24:00Z" };
		for (int i = 0; i < 5; i++) {
			UUID student = book.student("Student " + (char) ('A' + i), String.format("%04d", 84 + i * 30));
			book.order(student).monthly(course, "2026-10", "4000.00", "100.00")
				.confirm(i < 3 ? PaymentMethod.MANUAL_SLIP : PaymentMethod.GATEWAY, times[i]);
		}
		return book;
	}

	@Test
	void a_referenceFixtureCourseTotal20000NotIncludingTheServiceFees() {
		StudentPaymentCalculator.Result result = referenceFixture().calculate(dates("2026-10-08", "2026-10-08",
				List.of(), null));
		Summary s = only(result);
		assertThat(s.studentsPaid()).isEqualTo(5);
		assertThat(s.receiptCount()).isEqualTo(5);
		assertThat(s.rowCount()).isEqualTo(5);
		assertThat(s.coursePaymentTotal()).isEqualTo("20000.00");
		assertThat(s.serviceFeeTotal()).isEqualTo("500.00");
		assertThat(s.postageTotal()).isEqualTo("0.00");
		assertThat(s.receivedTotal()).isEqualTo("20500.00");
		assertThat(s.byMethod()).containsExactly(new MethodTotal("ONLINE", false, "8000.00", 2),
				new MethodTotal("BANK_TRANSFER", false, "12000.00", 3));
		assertThat(result.rows()).allSatisfy(row -> {
			assertThat(row.amount()).isEqualTo("4000.00");
			assertThat(row.serviceFee()).isEqualTo("100.00");
			assertThat(row.reason()).isEqualTo("2026-10");
			assertThat(row.dateOnly()).isFalse();
		});
		// B: index numbers keep their leading zeros.
		assertThat(result.rows()).extracting(StudentPaymentRow::indexNumber).contains("0084", "0114");
		// Rows are ordered by payment date.
		assertThat(result.rows()).extracting(StudentPaymentRow::recordedAt).isSorted();
		assertThat(recon(result).grossCoursePayments()).isEqualTo("20000.00");
		// A single day is labelled once.
		assertThat(result.report().filters().periodLabel()).isEqualTo("08 Oct 2026");
		assertThat(recon(result).refunds()).isEqualTo("0.00");
	}

	@Test
	void c_datesBoundariesUseTheTenantTimezone() {
		Book book = new Book();
		UUID course = book.course("C");
		UUID s = book.student("S", "0001");
		book.order(s).monthly(course, "2026-10", "1000.00", "0.00").confirm(PaymentMethod.GATEWAY,
				"2026-10-07T18:29:59Z"); // 07 Oct 23:59:59 local
		book.order(s).monthly(course, "2026-10", "2000.00", "0.00").confirm(PaymentMethod.GATEWAY,
				"2026-10-07T18:30:00Z"); // 08 Oct 00:00 local
		book.order(s).monthly(course, "2026-10", "4000.00", "0.00").confirm(PaymentMethod.GATEWAY,
				"2026-10-08T18:29:59Z"); // 08 Oct 23:59:59 local
		book.order(s).monthly(course, "2026-10", "8000.00", "0.00").confirm(PaymentMethod.GATEWAY,
				"2026-10-08T18:30:00Z"); // 09 Oct 00:00 local
		assertThat(only(book.calculate(dates("2026-10-08", "2026-10-08", List.of(), null))).coursePaymentTotal())
			.isEqualTo("6000.00");
		assertThat(only(book.calculate(dates("2026-10-07", "2026-10-08", List.of(), null))).coursePaymentTotal())
			.isEqualTo("7000.00");
		assertThat(only(book.calculate(dates("2026-10-08", "2026-10-09", List.of(), null))).coursePaymentTotal())
			.isEqualTo("14000.00");
	}

	@Test
	void d_manualReceiptIsDatedByReceivedOnAndMonthsIgnoreTheReceiptDate() {
		Book book = new Book();
		UUID course = book.course("C");
		UUID s = book.student("S", "0001");
		Order order = book.order(s).monthly(course, "2026-10", "3000.00", "0.00");
		// Received 15 Sep (backdated), recorded 20 Oct.
		order.receipt("CASH", "2026-09-15", "2026-10-20T05:00:00Z", "0.00");

		StudentPaymentCalculator.Result sep15 = book.calculate(dates("2026-09-15", "2026-09-15", List.of(), null));
		assertThat(only(sep15).coursePaymentTotal()).isEqualTo("3000.00");
		StudentPaymentRow row = sep15.rows().get(0);
		assertThat(row.dateOnly()).isTrue();
		assertThat(row.paymentDate()).isEqualTo("2026-09-15");
		assertThat(row.recordedAt()).isEqualTo("2026-10-20T05:00:00Z");
		assertThat(row.method()).isEqualTo("CASH");
		assertThat(row.manual()).isTrue();
		assertThat(only(sep15).byMethod()).containsExactly(new MethodTotal("CASH", true, "3000.00", 1));
		// Not found by its ledger (recording) date.
		assertThat(book.calculate(dates("2026-10-20", "2026-10-20", List.of(), null)).rows()).isEmpty();
		// MONTHS: the billing month decides, never the receipt date.
		assertThat(only(book.calculate(months(List.of(), null, "2026-10"))).coursePaymentTotal()).isEqualTo("3000.00");
		assertThat(book.calculate(months(List.of(), null, "2026-09")).rows()).isEmpty();
	}

	@Test
	void e_monthsAcrossYearsAndAnySelectedPeriod() {
		Book book = new Book();
		UUID course = book.course("C");
		UUID s = book.student("S", "0001");
		book.order(s).monthly(course, "2025-12", "1500.00", "0.00").monthly(course, "2026-01", "2500.00", "0.00")
			.confirm(PaymentMethod.GATEWAY, "2025-12-20T04:00:00Z");
		book.order(s).monthly(course, "2026-02", "999.00", "0.00").confirm(PaymentMethod.GATEWAY,
				"2026-01-20T04:00:00Z");
		StudentPaymentCalculator.Result both = book.calculate(months(List.of(), null, "2026-01", "2025-12"));
		assertThat(only(both).coursePaymentTotal()).isEqualTo("4000.00");
		assertThat(only(both).receiptCount()).isEqualTo(1);
		assertThat(both.rows()).extracting(StudentPaymentRow::reason).containsExactly("2025-12", "2026-01");
		assertThat(both.report().filters().periodLabel()).isEqualTo("Dec 2025, Jan 2026");
		assertThat(only(book.calculate(months(List.of(), null, "2026-01"))).coursePaymentTotal()).isEqualTo("2500.00");
	}

	@Test
	void fg_multiCourseMultiMonthReceiptIsNeverDuplicatedAndTheCourseFilterDropsSiblingRowsAndFees() {
		Book book = new Book();
		UUID x = book.course("X");
		UUID y = book.course("Y");
		UUID s = book.student("S", "0001");
		Order order = book.order(s).monthly(x, "2026-10", "3000.00", "100.00").monthly(x, "2026-11", "3000.00", "100.00")
			.monthly(y, "2026-10", "2000.00", "100.00");
		ReportLedgerEntry entry = order.confirm(PaymentMethod.GATEWAY, "2026-10-08T04:00:00Z");

		StudentPaymentCalculator.Result all = book.calculate(dates("2026-10-01", "2026-10-31", List.of(), null));
		assertThat(all.rows()).hasSize(3);
		assertThat(sum(all.rows(), StudentPaymentRow::amount)).isEqualByComparingTo("8000.00");
		assertThat(sum(all.rows(), StudentPaymentRow::serviceFee)).isEqualByComparingTo("300.00");
		assertThat(sum(all.rows(), StudentPaymentRow::amount).add(sum(all.rows(), StudentPaymentRow::serviceFee))
			.add(sum(all.rows(), StudentPaymentRow::postage))).isEqualByComparingTo(entry.amount());
		assertThat(only(all).receiptCount()).isEqualTo(1);
		assertThat(only(all).studentsPaid()).isEqualTo(1);

		StudentPaymentCalculator.Result onlyX = book.calculate(dates("2026-10-01", "2026-10-31", List.of(x), null));
		assertThat(onlyX.rows()).extracting(StudentPaymentRow::courseId).containsOnly(x);
		assertThat(only(onlyX).coursePaymentTotal()).isEqualTo("6000.00");
		assertThat(only(onlyX).serviceFeeTotal()).isEqualTo("200.00");
		assertThat(onlyX.report().filters().courses()).extracting(c -> c.id()).containsExactly(x);
		// MONTHS Oct, course X: only the Oct line.
		StudentPaymentCalculator.Result octX = book.calculate(months(List.of(x), null, "2026-10"));
		assertThat(octX.rows()).hasSize(1);
		assertThat(only(octX).coursePaymentTotal()).isEqualTo("3000.00");
	}

	@Test
	void fg_partialReceiptFilterAllocatesOnlyItsShareOfTheStaffAdjustment() {
		Book book = new Book();
		UUID x = book.course("X");
		UUID y = book.course("Y");
		UUID s = book.student("S", "0001");
		book.order(s).monthly(x, "2026-10", "4000.00", "100.00")
			.monthly(y, "2026-10", "4000.00", "100.00")
			.receipt("CASH", "2026-10-08", "2026-10-08T04:00:00Z", "820.00");

		StudentPaymentCalculator.Result all = book.calculate(dates("2026-10-08", "2026-10-08", List.of(), null));
		assertThat(recon(all).staffAdjustments().amount()).isEqualTo("820.00");
		StudentPaymentCalculator.Result onlyX = book.calculate(dates("2026-10-08", "2026-10-08", List.of(x), null));
		assertThat(recon(onlyX).staffAdjustments().count()).isEqualTo(1);
		assertThat(recon(onlyX).staffAdjustments().amount()).isEqualTo("410.00");
		assertThat(only(onlyX).coursePaymentTotal()).isEqualTo("3600.00");
		assertThat(only(onlyX).serviceFeeTotal()).isEqualTo("90.00");
	}

	@Test
	void h_aStudentWithTwoReceiptsIsCountedOnce() {
		Book book = new Book();
		UUID course = book.course("C");
		UUID s = book.student("S", "0001");
		book.order(s).monthly(course, "2026-10", "1000.00", "0.00").confirm(PaymentMethod.GATEWAY,
				"2026-10-08T04:00:00Z");
		book.order(s).monthly(course, "2026-11", "1000.00", "0.00").confirm(PaymentMethod.MANUAL_SLIP,
				"2026-10-08T05:00:00Z");
		Summary summary = only(book.calculate(dates("2026-10-08", "2026-10-08", List.of(), null)));
		assertThat(summary.studentsPaid()).isEqualTo(1);
		assertThat(summary.receiptCount()).isEqualTo(2);
		assertThat(summary.byMethod().stream().mapToLong(MethodTotal::receiptCount).sum()).isEqualTo(2);
	}

	@Test
	void j_nonCashConfirmationsNeverBecomeRows() {
		Book book = new Book();
		UUID course = book.course("C");
		UUID s = book.student("S", "0001");
		book.order(s).monthly(course, "2026-10", "4000.00", "0.00").confirm(PaymentMethod.STAFF_GRANTED,
				"2026-10-08T04:00:00Z");
		book.order(s).monthly(course, "2026-10", "0.00", "0.00").confirm(PaymentMethod.FREE, "2026-10-08T04:00:00Z");
		StudentPaymentCalculator.Result result = book.calculate(dates("2026-10-08", "2026-10-08", List.of(), null));
		assertThat(result.rows()).isEmpty();
		assertThat(result.report().sections()).isEmpty();
		assertThat(recon(result).excludedNonCash().count()).isEqualTo(1);
		assertThat(recon(result).excludedNonCash().amount()).isEqualTo("4000.00");
	}

	@Test
	void k_discountIsNetStaffAdjustmentIsProRataRefundIsANegativeRowAndPostageIsSeparate() {
		Book book = new Book();
		UUID course = book.course("C");
		UUID other = book.course("D");
		UUID s = book.student("S", "0001");
		// Discount: unit 4000 - 500 discount + fee 100.
		book.order(s).monthly(course, "2026-10", "4000.00", "100.00", "500.00").confirm(PaymentMethod.GATEWAY,
				"2026-10-08T03:00:00Z");
		// Manual receipt of 4,100 with a 410 staff adjustment: 3,690 received.
		book.order(s).monthly(course, "2026-10", "4000.00", "100.00").receipt("CASH", "2026-10-08",
				"2026-10-08T04:00:00Z", "410.00");
		// Gateway 4,000 + fee 100 + postage 200 (other course), then a 430 refund.
		Order withPostage = book.order(s).monthly(other, "2026-10", "4000.00", "100.00").postage(other, "200.00");
		ReportLedgerEntry paid = withPostage.confirm(PaymentMethod.GATEWAY, "2026-10-08T05:00:00Z");
		withPostage.refund(paid, "-430.00", "2026-10-08T06:00:00Z");

		StudentPaymentCalculator.Result result = book.calculate(dates("2026-10-08", "2026-10-08", List.of(), null));
		List<StudentPaymentRow> rows = result.rows();
		assertThat(rows).hasSize(4);
		// The date-only receipt sorts at its local midnight, before the timed payments of that day.
		// Received 3,690 = 4,100 x 0.9: course 3,600, fee 90 (scaled pro rata, never absorbed by fees).
		assertThat(rows.get(0).amount()).isEqualTo("3600.00");
		assertThat(rows.get(0).serviceFee()).isEqualTo("90.00");
		assertThat(rows.get(0).dateOnly()).isTrue();
		assertThat(rows.get(1).amount()).isEqualTo("3500.00");
		assertThat(rows.get(1).serviceFee()).isEqualTo("100.00");
		assertThat(rows.get(2).amount()).isEqualTo("4000.00");
		assertThat(rows.get(2).serviceFee()).isEqualTo("100.00");
		assertThat(rows.get(2).postage()).isEqualTo("200.00");
		// Refund 430 of 4,300: course -400, fee -10, postage -20; inherits the original method.
		StudentPaymentRow refund = rows.get(3);
		assertThat(refund.refund()).isTrue();
		assertThat(refund.amount()).isEqualTo("-400.00");
		assertThat(refund.serviceFee()).isEqualTo("-10.00");
		assertThat(refund.postage()).isEqualTo("-20.00");
		assertThat(refund.method()).isEqualTo("ONLINE");
		assertThat(refund.receiptId()).isEqualTo(paid.paymentId());

		Summary summary = only(result);
		assertThat(summary.coursePaymentTotal()).isEqualTo("10700.00");
		assertThat(summary.serviceFeeTotal()).isEqualTo("280.00");
		assertThat(summary.postageTotal()).isEqualTo("180.00");
		assertThat(summary.receivedTotal()).isEqualTo("11160.00");
		assertThat(summary.receiptCount()).isEqualTo(3);
		assertThat(summary.refundCount()).isEqualTo(1);
		assertThat(summary.byMethod()).containsExactly(new MethodTotal("ONLINE", false, "7100.00", 2),
				new MethodTotal("CASH", true, "3600.00", 1));
		CurrencyReconciliation recon = recon(result);
		assertThat(recon.grossCoursePayments()).isEqualTo("11100.00");
		assertThat(recon.refunds()).isEqualTo("400.00");
		assertThat(recon.staffAdjustments().count()).isEqualTo(1);
		assertThat(recon.staffAdjustments().amount()).isEqualTo("410.00");
		// Cash received = sum of the ledger entries.
		BigDecimal ledger = book.entries().stream().map(ReportLedgerEntry::amount).reduce(BigDecimal.ZERO,
				BigDecimal::add);
		assertThat(new BigDecimal(summary.receivedTotal())).isEqualByComparingTo(ledger);
	}

	@Test
	void feeOnlyAndPostageOnlyRowsAreKeptAndAllZeroRowsOmitted() {
		Book book = new Book();
		UUID course = book.course("C");
		UUID free = book.course("Free course");
		UUID s = book.student("S", "0001");
		// Line 1 fully discounted but carries the fee; line 2 all zero; course "free" only postage.
		book.order(s).monthly(course, "2026-10", "1000.00", "100.00", "1000.00")
			.monthly(course, "2026-11", "500.00", "0.00", "500.00")
			.monthly(free, "2026-10", "0.00", "0.00")
			.postage(free, "250.00")
			.confirm(PaymentMethod.GATEWAY, "2026-10-08T04:00:00Z");
		StudentPaymentCalculator.Result result = book.calculate(dates("2026-10-08", "2026-10-08", List.of(), null));
		assertThat(result.rows()).hasSize(2);
		assertThat(result.rows().get(0).amount()).isEqualTo("0.00");
		assertThat(result.rows().get(0).serviceFee()).isEqualTo("100.00");
		assertThat(result.rows().get(1).courseId()).isEqualTo(free);
		assertThat(result.rows().get(1).postage()).isEqualTo("250.00");
		assertThat(only(result).receivedTotal()).isEqualTo("350.00");
	}

	@Test
	void reasonsMethodsAndMonthAttributionOfModuleSessionAndLegacyLines() {
		Book book = new Book();
		UUID course = book.course("C");
		UUID s = book.student("S", "0001");
		UUID octLesson = book.lesson(YearMonth.of(2026, 10));
		UUID undated = book.lesson(null);
		book.order(s).monthly(course, "2026-10", "1000.00", "0.00").module(course, "Organic chemistry", "700.00",
				"0.00").lesson(course, octLesson, "Paper class 3", "300.00", "0.00")
			.lesson(course, undated, "Revision", "200.00", "0.00").confirm(PaymentMethod.MANUAL_SLIP,
					"2026-10-08T04:00:00Z");
		book.order(s).legacy(course, "999.00").confirm(null, "2026-10-08T05:00:00Z");

		StudentPaymentCalculator.Result dates = book.calculate(dates("2026-10-08", "2026-10-08", List.of(), null));
		assertThat(dates.rows()).extracting(StudentPaymentRow::reason).containsExactly("2026-10",
				"Module: Organic chemistry", "Session: Paper class 3", "Session: Revision", "Course payment");
		assertThat(dates.rows().get(4).method()).isEqualTo("UNKNOWN");
		assertThat(dates.rows().get(4).scope()).isEqualTo("LEGACY");
		assertThat(only(dates).byMethod()).extracting(MethodTotal::method).containsExactly("BANK_TRANSFER",
				"UNKNOWN");

		StudentPaymentCalculator.Result oct = book.calculate(months(List.of(), null, "2026-10"));
		assertThat(oct.rows()).extracting(StudentPaymentRow::reason).containsExactly("2026-10",
				"Session: Paper class 3");
		// Month-less parts of the entries it is given are reconciled: module 700 + undated session 200 +
		// the legacy 999 (the service itself never loads legacy orders in MONTHS mode - 3-R.3).
		assertThat(recon(oct).excludedNotMonthAttributable().amount()).isEqualTo("1899.00");
		assertThat(recon(oct).excludedNotMonthAttributable().count()).isEqualTo(2);
	}

	@Test
	void refundsFollowTheLineMonthInMonthsAndTheRefundDateInDates() {
		Book book = new Book();
		UUID course = book.course("C");
		UUID s = book.student("S", "0001");
		Order order = book.order(s).monthly(course, "2026-09", "1000.00", "0.00");
		ReportLedgerEntry paid = order.confirm(PaymentMethod.GATEWAY, "2026-09-05T04:00:00Z");
		order.refund(paid, "-1000.00", "2026-10-08T04:00:00Z");
		StudentPaymentCalculator.Result sep = book.calculate(months(List.of(), null, "2026-09"));
		assertThat(only(sep).coursePaymentTotal()).isEqualTo("0.00");
		assertThat(only(sep).refundCount()).isEqualTo(1);
		assertThat(only(sep).studentsPaid()).isEqualTo(1);
		StudentPaymentCalculator.Result octDay = book.calculate(dates("2026-10-08", "2026-10-08", List.of(), null));
		assertThat(octDay.rows()).hasSize(1);
		assertThat(octDay.rows().get(0).refund()).isTrue();
		assertThat(only(octDay).coursePaymentTotal()).isEqualTo("-1000.00");
		assertThat(only(octDay).receiptCount()).isZero();
		assertThat(only(octDay).studentsPaid()).isZero();
	}

	@Test
	void paymentMethodFilterUsesTheChannel() {
		Book book = referenceFixture();
		assertThat(only(book.calculate(dates("2026-10-08", "2026-10-08", List.of(), PaymentMethod.GATEWAY)))
			.coursePaymentTotal()).isEqualTo("8000.00");
		assertThat(only(book.calculate(dates("2026-10-08", "2026-10-08", List.of(), PaymentMethod.MANUAL_SLIP)))
			.coursePaymentTotal()).isEqualTo("12000.00");
		assertThat(book.calculate(dates("2026-10-08", "2026-10-08", List.of(), PaymentMethod.MANUAL_RECEIPT)).rows())
			.isEmpty();
	}

}
