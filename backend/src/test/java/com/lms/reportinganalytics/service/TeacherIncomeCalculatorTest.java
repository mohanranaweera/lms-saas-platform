package com.lms.reportinganalytics.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry.ReportCourseAllocation;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry.ReportLineAllocation;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry.SettledAllocation;
import com.lms.ledgersettlementmanagement.api.TeacherIncomeLedgerApi.PaidSettlement;
import com.lms.ledgersettlementmanagement.api.TeacherIncomeLedgerApi.ShareRate;
import com.lms.reportinganalytics.service.TeacherIncomeCriteria.PeriodMode;
import com.lms.reportinganalytics.service.TeacherIncomeReport.CourseRow;
import com.lms.reportinganalytics.service.TeacherIncomeReport.CurrencyReconciliation;
import com.lms.reportinganalytics.service.TeacherIncomeReport.Section;
import com.lms.reportinganalytics.service.TeacherIncomeReport.TeacherBlock;
import com.lms.reportinganalytics.service.TeacherIncomeReport.TeacherRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * RPT-01 metric dictionary (spec sections 2, 2-R, 3) on hand-built ledger
 * inputs - every financial rule proven without a database.
 */
class TeacherIncomeCalculatorTest {

	private static final ZoneId ZONE = ZoneId.of("Asia/Colombo");

	private static final UUID T1 = UUID.fromString("10000000-0000-0000-0000-000000000001");

	private static final UUID T2 = UUID.fromString("10000000-0000-0000-0000-000000000002");

	private static final UUID T3 = UUID.fromString("10000000-0000-0000-0000-000000000003");

	private static final UUID A = UUID.fromString("20000000-0000-0000-0000-00000000000a");

	private static final UUID B = UUID.fromString("20000000-0000-0000-0000-00000000000b");

	private static final UUID C = UUID.fromString("20000000-0000-0000-0000-00000000000c");

	private static final UUID X = UUID.fromString("20000000-0000-0000-0000-0000000000ff");

	private final Map<UUID, UUID> teacherByCourse = new HashMap<>(Map.of(A, T1, B, T2, C, T1, X, T3));

	private final Map<UUID, String> courseNames = Map.of(A, "Accounting", B, "Biology", C, "chemistry", X, "Other");

	private final List<ShareRate> rates = new ArrayList<>(List.of(new ShareRate(T1, d("75.00"), LocalDate.of(2025, 1, 1)),
			new ShareRate(T1, d("50.00"), LocalDate.of(2025, 6, 1)), new ShareRate(T2, d("40.00"),
					LocalDate.of(2025, 1, 1))));

	private static BigDecimal d(String value) {
		return new BigDecimal(value);
	}

	// ------------------------------------------------------------------
	// Builders.
	// ------------------------------------------------------------------

	private static ReportLineAllocation monthLine(int lineNo, int year, int month, String amount) {
		return new ReportLineAllocation(UUID.randomUUID(), lineNo, "MONTHLY", year, month, d(amount));
	}

	private static ReportCourseAllocation course(UUID courseId, String amount, ReportLineAllocation... lines) {
		return new ReportCourseAllocation(courseId, d(amount), List.of(lines), null);
	}

	private static ReportLedgerEntry confirmed(String amount, String at, PaymentMethod method,
			ReportCourseAllocation... courses) {
		return entry(false, amount, at, "LKR", method, true, courses);
	}

	private static ReportLedgerEntry entry(boolean refund, String amount, String at, String currency,
			PaymentMethod method, boolean lineOrder, ReportCourseAllocation... courses) {
		return new ReportLedgerEntry(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), refund, d(amount),
				Instant.parse(at), null, currency, method, lineOrder, List.of(courses));
	}

	private static TeacherIncomeCriteria dates(String start, String end, PaymentMethod method, UUID... teachers) {
		return new TeacherIncomeCriteria(List.of(teachers), method, PeriodMode.DATES, null, LocalDate.parse(start),
				LocalDate.parse(end));
	}

	private static TeacherIncomeCriteria months(List<String> months, UUID... teachers) {
		TreeSet<YearMonth> set = new TreeSet<>();
		months.forEach(m -> set.add(YearMonth.parse(m)));
		return new TeacherIncomeCriteria(List.of(teachers), null, PeriodMode.MONTHS, set, null, null);
	}

	private TeacherIncomeReport run(TeacherIncomeCriteria criteria, List<ReportLedgerEntry> entries) {
		return run(criteria, entries, List.of(), List.of());
	}

	private TeacherIncomeReport run(TeacherIncomeCriteria criteria, List<ReportLedgerEntry> entries,
			List<ReportLedgerEntry> monthWindow, List<PaidSettlement> payouts) {
		List<TeacherRef> refs = criteria.teacherIds().stream().map(id -> new TeacherRef(id, "Teacher " + id)).toList();
		return TeacherIncomeCalculator.calculate(new TeacherIncomeCalculator.Input(criteria, ZONE,
				Instant.parse("2026-10-22T04:30:00Z"), "Institute", refs, entries, monthWindow, teacherByCourse,
				courseNames, rates, payouts, "LKR"));
	}

	private static Section section(TeacherIncomeReport report, String currency) {
		return report.sections().stream().filter(s -> s.currency().equals(currency)).findFirst().orElseThrow();
	}

	private static TeacherBlock teacher(Section section, UUID id) {
		return section.teachers().stream().filter(t -> t.teacherId().equals(id)).findFirst().orElseThrow();
	}

	private static CourseRow row(TeacherBlock teacher, UUID courseId) {
		return teacher.courses().stream().filter(c -> c.courseId().equals(courseId)).findFirst().orElseThrow();
	}

	private static CurrencyReconciliation recon(TeacherIncomeReport report, String currency) {
		return report.reconciliation().byCurrency().stream().filter(r -> r.currency().equals(currency)).findFirst()
			.orElseThrow();
	}

	// ------------------------------------------------------------------
	// Tests.
	// ------------------------------------------------------------------

	@Test
	void referenceFixtureNetAtTheRateInEffectAndFacilityFeeAsServiceFee() {
		// 4,100 paid: course share 4,000 (A2) + facility fee 100. Rate 75% (a later
		// 50% rate from June does not apply to a March payment).
		TeacherIncomeReport report = run(dates("2025-03-01", "2025-03-31", null, T1),
				List.of(confirmed("4100.00", "2025-03-10T04:00:00Z", PaymentMethod.GATEWAY,
						course(A, "4000.00", monthLine(1, 2025, 3, "4000.00")))));
		Section lkr = section(report, "LKR");
		assertThat(lkr.totals().revenue()).isEqualTo("4000.00");
		assertThat(lkr.totals().teacherIncome()).isEqualTo("3000.00");
		assertThat(lkr.totals().commission()).isEqualTo("1000.00");
		assertThat(lkr.totals().serviceFees()).isEqualTo("100.00");
		assertThat(lkr.totals().paymentCount()).isEqualTo(1);
		assertThat(lkr.totals().teacherCount()).isEqualTo(1);
		assertThat(lkr.totals().payins()).isNull();
		CourseRow a = row(teacher(lkr, T1), A);
		assertThat(a.revenue()).isEqualTo("4000.00");
		assertThat(a.teacherIncome()).isEqualTo("3000.00");
		assertThat(a.commission()).isEqualTo("1000.00");
		assertThat(a.rateMissing()).isFalse();
		assertThat(report.filters().periodLabel()).isEqualTo("01 Mar 2025 to 31 Mar 2025");
	}

	@Test
	void multiCourseOrderIsCountedOncePerTeacherAndSectionAndNeverDuplicated() {
		ReportLedgerEntry multi = confirmed("7200.00", "2025-03-15T04:00:00Z", PaymentMethod.MANUAL_SLIP,
				course(A, "3000.00", monthLine(1, 2025, 3, "3000.00")),
				course(C, "1000.00", monthLine(2, 2025, 3, "1000.00")),
				course(B, "3000.00", monthLine(3, 2025, 3, "3000.00")));
		TeacherIncomeReport report = run(dates("2025-03-01", "2025-03-31", null, T1, T2), List.of(multi));
		Section lkr = section(report, "LKR");
		assertThat(lkr.totals().revenue()).isEqualTo("7000.00");
		assertThat(lkr.totals().serviceFees()).isEqualTo("200.00");
		assertThat(lkr.totals().paymentCount()).isEqualTo(1);
		TeacherBlock t1 = teacher(lkr, T1);
		assertThat(t1.totals().paymentCount()).isEqualTo(1);
		assertThat(t1.courses()).extracting(CourseRow::paymentCount).containsExactly(1L, 1L);
		assertThat(t1.totals().revenue()).isEqualTo("4000.00");
		assertThat(teacher(lkr, T2).totals().teacherIncome()).isEqualTo("1200.00");
		// Sorting: courses by name (case-insensitive), then id.
		assertThat(t1.courses()).extracting(CourseRow::courseName).containsExactly("Accounting", "chemistry");
		// Subtotals reconcile with the KPIs.
		BigDecimal sum = lkr.teachers().stream().map(t -> d(t.totals().revenue())).reduce(BigDecimal.ZERO,
				BigDecimal::add);
		assertThat(sum).isEqualByComparingTo(lkr.totals().revenue());
		CurrencyReconciliation r = recon(report, "LKR");
		assertThat(d(r.grossRevenue()).subtract(d(r.refunds()))).isEqualByComparingTo(lkr.totals().revenue());
	}

	@Test
	void teacherShareIsRoundedPerRateBucketAndCommissionIsTheRemainder() {
		rates.add(new ShareRate(T1, d("33.33"), LocalDate.of(2025, 3, 20)));
		TeacherIncomeReport report = run(dates("2025-03-01", "2025-03-31", null, T1),
				List.of(confirmed("100.01", "2025-03-10T04:00:00Z", PaymentMethod.GATEWAY, course(A, "100.01")),
						confirmed("100.01", "2025-03-25T04:00:00Z", PaymentMethod.GATEWAY, course(A, "100.01"))));
		CourseRow a = row(teacher(section(report, "LKR"), T1), A);
		// 100.01 x 75% = 75.0075 -> 75.01 ; 100.01 x 33.33% = 33.333... -> 33.33.
		assertThat(a.teacherIncome()).isEqualTo("108.34");
		assertThat(a.commission()).isEqualTo("91.68");
		assertThat(d(a.teacherIncome()).add(d(a.commission()))).isEqualByComparingTo(a.revenue());
	}

	@Test
	void datesModeUsesTheTenantLocalDateForTheRateAndRefundsByRefundDateWithCountZero() {
		// 2025-05-31T18:30Z is 1 June 00:00 in Colombo -> the June (50%) rate applies.
		ReportLedgerEntry june = confirmed("1000.00", "2025-05-31T18:30:00Z", PaymentMethod.GATEWAY,
				course(A, "1000.00"));
		ReportLedgerEntry refund = entry(true, "-400.00", "2025-06-02T04:00:00Z", "LKR", PaymentMethod.GATEWAY, true,
				course(A, "-400.00"));
		CourseRow a = row(teacher(section(run(dates("2025-06-01", "2025-06-30", null, T1), List.of(june, refund)),
				"LKR"), T1), A);
		assertThat(a.revenue()).isEqualTo("600.00");
		assertThat(a.teacherIncome()).isEqualTo("300.00");
		assertThat(a.paymentCount()).isEqualTo(1);
		CourseRow onlyRefund = row(teacher(section(run(dates("2025-06-02", "2025-06-30", null, T1), List.of(refund)),
				"LKR"), T1), A);
		assertThat(onlyRefund.revenue()).isEqualTo("-400.00");
		assertThat(onlyRefund.paymentCount()).isZero();
	}

	@Test
	void monthsModeTakesOnlyLinesBilledInTheSelectedMonthsAcrossYearsAndRefundsFollowTheLineMonth() {
		ReportLedgerEntry paid = confirmed("4000.00", "2024-12-20T04:00:00Z", PaymentMethod.GATEWAY,
				course(A, "4000.00", monthLine(1, 2024, 12, "1500.00"), monthLine(2, 2025, 1, "2500.00")));
		ReportLedgerEntry refund = entry(true, "-400.00", "2025-08-01T04:00:00Z", "LKR", PaymentMethod.GATEWAY, true,
				course(A, "-400.00", monthLine(1, 2024, 12, "-150.00"), monthLine(2, 2025, 1, "-250.00")));
		TeacherIncomeReport both = run(months(List.of("2025-01", "2024-12"), T1), List.of(paid, refund));
		assertThat(section(both, "LKR").totals().revenue()).isEqualTo("3600.00");
		assertThat(both.filters().months()).containsExactly("2024-12", "2025-01");
		assertThat(both.filters().periodLabel()).isEqualTo("Dec 2024, Jan 2025");
		TeacherIncomeReport jan = run(months(List.of("2025-01"), T1), List.of(paid, refund));
		CourseRow a = row(teacher(section(jan, "LKR"), T1), A);
		assertThat(a.revenue()).isEqualTo("2250.00");
		assertThat(a.paymentCount()).isEqualTo(1);
		// The facility fee/postage of an entry is counted once if any line is in scope.
		assertThat(section(jan, "LKR").totals().serviceFees()).isEqualTo("0.00");
	}

	@Test
	void monthsModeReportsLegacyAndModuleRevenueAsNotMonthAttributable() {
		ReportLedgerEntry legacy = entry(false, "999.00", "2025-03-20T04:00:00Z", "LKR", PaymentMethod.GATEWAY, false,
				course(A, "999.00"));
		ReportLedgerEntry module = confirmed("500.00", "2025-03-21T04:00:00Z", PaymentMethod.GATEWAY,
				course(A, "500.00", new ReportLineAllocation(UUID.randomUUID(), 1, "MODULE", null, null, d("500.00"))));
		TeacherIncomeReport report = run(months(List.of("2025-03"), T1), List.of(), List.of(legacy, module),
				List.of());
		assertThat(report.sections()).isEmpty();
		CurrencyReconciliation r = recon(report, "LKR");
		assertThat(r.excludedNotMonthAttributable().count()).isEqualTo(2);
		assertThat(r.excludedNotMonthAttributable().amount()).isEqualTo("1499.00");
	}

	@Test
	void paymentMethodFilterNonCashExclusionAndStrictTeacherFilter() {
		List<ReportLedgerEntry> entries = List.of(
				confirmed("1000.00", "2025-03-02T04:00:00Z", PaymentMethod.GATEWAY, course(A, "1000.00")),
				confirmed("2000.00", "2025-03-03T04:00:00Z", PaymentMethod.MANUAL_SLIP, course(A, "2000.00")),
				confirmed("3000.00", "2025-03-04T04:00:00Z", PaymentMethod.MANUAL_RECEIPT, course(A, "3000.00")),
				confirmed("4000.00", "2025-03-05T04:00:00Z", PaymentMethod.STAFF_GRANTED, course(A, "4000.00")),
				confirmed("0.00", "2025-03-05T05:00:00Z", PaymentMethod.FREE),
				confirmed("5000.00", "2025-03-06T04:00:00Z", PaymentMethod.GATEWAY, course(X, "5000.00")));
		TeacherIncomeReport all = run(dates("2025-03-01", "2025-03-31", null, T1), entries);
		assertThat(section(all, "LKR").totals().revenue()).isEqualTo("6000.00");
		assertThat(section(all, "LKR").totals().paymentCount()).isEqualTo(3);
		assertThat(section(all, "LKR").teachers()).extracting(TeacherBlock::teacherId).containsExactly(T1);
		assertThat(recon(all, "LKR").excludedNonCash().count()).isEqualTo(1);
		assertThat(recon(all, "LKR").excludedNonCash().amount()).isEqualTo("4000.00");
		assertThat(section(run(dates("2025-03-01", "2025-03-31", PaymentMethod.MANUAL_SLIP, T1), entries), "LKR")
			.totals()
			.revenue()).isEqualTo("2000.00");
		assertThat(section(run(dates("2025-03-01", "2025-03-31", PaymentMethod.MANUAL_RECEIPT, T1), entries), "LKR")
			.totals()
			.revenue()).isEqualTo("3000.00");
	}

	@Test
	void settledAllocationsUseTheSettlementTeacherAndRateAndReportTheBasisDifference() {
		// Course A now belongs to T2, but the allocation was settled to T1 at 60%
		// with a stored item of 4100 (pre-A2 basis: fee inside the share).
		teacherByCourse.put(A, T2);
		ReportCourseAllocation settled = new ReportCourseAllocation(A, d("4000.00"),
				List.of(monthLine(1, 2025, 3, "4000.00")),
				new SettledAllocation(UUID.randomUUID(), T1, d("60.00"), d("4100.00"), "PAID"));
		ReportLedgerEntry entry = new ReportLedgerEntry(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), false,
				d("4100.00"), Instant.parse("2025-03-10T04:00:00Z"), null, "LKR", PaymentMethod.GATEWAY, true,
				List.of(settled));
		TeacherIncomeReport report = run(dates("2025-03-01", "2025-03-31", null, T1, T2), List.of(entry));
		Section lkr = section(report, "LKR");
		assertThat(lkr.teachers()).extracting(TeacherBlock::teacherId).containsExactly(T1);
		assertThat(row(teacher(lkr, T1), A).teacherIncome()).isEqualTo("2400.00");
		assertThat(recon(report, "LKR").settledBasisDifference()).isEqualTo("100.00");
		assertThat(report.reconciliation().notes()).anyMatch(n -> n.contains("already-settled"));
	}

	@Test
	void missingRateFlagsTheRowAndCountsUnratedRevenue() {
		rates.clear();
		TeacherIncomeReport report = run(dates("2025-03-01", "2025-03-31", null, T1),
				List.of(confirmed("1000.00", "2025-03-02T04:00:00Z", PaymentMethod.GATEWAY, course(A, "1000.00"))));
		Section lkr = section(report, "LKR");
		CourseRow a = row(teacher(lkr, T1), A);
		assertThat(a.rateMissing()).isTrue();
		assertThat(a.teacherIncome()).isNull();
		assertThat(a.commission()).isNull();
		assertThat(lkr.totals().unratedRevenue()).isEqualTo("1000.00");
		assertThat(lkr.totals().teacherIncome()).isEqualTo("0.00");
	}

	@Test
	void currenciesAreSeparateSectionsSortedAndNeverSummed() {
		TeacherIncomeReport report = run(dates("2025-03-01", "2025-03-31", null, T1),
				List.of(entry(false, "100.00", "2025-03-02T04:00:00Z", "USD", PaymentMethod.GATEWAY, true,
						course(A, "100.00")),
						confirmed("1000.00", "2025-03-02T04:00:00Z", PaymentMethod.GATEWAY, course(A, "1000.00"))));
		assertThat(report.sections()).extracting(Section::currency).containsExactly("LKR", "USD");
		assertThat(section(report, "USD").totals().revenue()).isEqualTo("100.00");
		assertThat(section(report, "LKR").totals().revenue()).isEqualTo("1000.00");
		assertThat(report.reconciliation().byCurrency()).extracting(CurrencyReconciliation::currency)
			.containsExactly("LKR", "USD");
	}

	@Test
	void payoutsAreStoredPaidSettlementFiguresIncludingAdjustmentsAndBalanceIsTheMovement() {
		List<PaidSettlement> payouts = List.of(
				new PaidSettlement(UUID.randomUUID(), T1, "REGULAR", d("2500.00"), "LKR",
						Instant.parse("2025-03-31T10:00:00Z")),
				new PaidSettlement(UUID.randomUUID(), T1, "ADJUSTMENT", d("10.50"), "LKR",
						Instant.parse("2025-03-31T11:00:00Z")),
				new PaidSettlement(UUID.randomUUID(), T2, "REGULAR", d("99.00"), "LKR",
						Instant.parse("2025-03-31T11:00:00Z")));
		TeacherIncomeReport report = run(dates("2025-03-01", "2025-03-31", PaymentMethod.MANUAL_SLIP, T1),
				List.of(confirmed("4000.00", "2025-03-10T04:00:00Z", PaymentMethod.GATEWAY, course(A, "4000.00"))),
				List.of(), payouts);
		// The method filter removes the GATEWAY revenue but never the payouts.
		TeacherBlock t1 = teacher(section(report, "LKR"), T1);
		assertThat(t1.courses()).isEmpty();
		assertThat(t1.totals().payouts()).isEqualTo("2510.50");
		assertThat(t1.totals().balance()).isEqualTo("-2510.50");
		assertThat(section(report, "LKR").teachers()).hasSize(1);
	}

	@Test
	void unassignedCourseRevenueIsNeverShownButReconciled() {
		teacherByCourse.remove(A);
		TeacherIncomeReport report = run(dates("2025-03-01", "2025-03-31", null, T1),
				List.of(confirmed("1000.00", "2025-03-02T04:00:00Z", PaymentMethod.GATEWAY, course(A, "1000.00"))));
		assertThat(report.sections()).isEmpty();
		assertThat(recon(report, "LKR").unassignedRevenue()).isEqualTo("1000.00");
	}

}
