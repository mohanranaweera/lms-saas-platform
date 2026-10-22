package com.lms.reportinganalytics.service.pdf;

import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry.ReportCourseAllocation;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry.ReportLineAllocation;
import com.lms.ledgersettlementmanagement.api.TeacherIncomeLedgerApi.PaidSettlement;
import com.lms.ledgersettlementmanagement.api.TeacherIncomeLedgerApi.ShareRate;
import com.lms.reportinganalytics.service.TeacherIncomeCalculator;
import com.lms.reportinganalytics.service.TeacherIncomeCriteria;
import com.lms.reportinganalytics.service.TeacherIncomeCriteria.PeriodMode;
import com.lms.reportinganalytics.service.TeacherIncomeReport;
import com.lms.reportinganalytics.service.TeacherIncomeReport.TeacherRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RPT-01 sample datasets for the PDF tests and visual QA. Every dataset is
 * PRODUCED by {@link TeacherIncomeCalculator} from synthetic ledger inputs, so
 * KPIs, teacher totals and rows always reconcile exactly as in production.
 */
final class SampleReports {

	static final UUID T1 = UUID.fromString("10000000-0000-0000-0000-000000000001");

	static final UUID T2 = UUID.fromString("10000000-0000-0000-0000-000000000002");

	static final UUID T3 = UUID.fromString("10000000-0000-0000-0000-000000000003");

	private static final ZoneId ZONE = ZoneId.of("Asia/Colombo");

	private static final Instant GENERATED = Instant.parse("2026-10-22T10:00:00Z");

	private SampleReports() {
	}

	private static BigDecimal d(String value) {
		return new BigDecimal(value);
	}

	private static ReportCourseAllocation course(UUID courseId, String amount) {
		return new ReportCourseAllocation(courseId, d(amount), List.of(new ReportLineAllocation(UUID.randomUUID(), 1,
				"MONTHLY", 2025, 3, d(amount))), null);
	}

	private static ReportLedgerEntry entry(boolean refund, String amount, String at, String currency,
			PaymentMethod method, ReportCourseAllocation... courses) {
		return new ReportLedgerEntry(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), refund, d(amount),
				Instant.parse(at), null, currency, method, true, List.of(courses));
	}

	private static TeacherIncomeReport calculate(List<TeacherRef> teachers, List<ReportLedgerEntry> entries,
			Map<UUID, UUID> teacherByCourse, Map<UUID, String> names, List<ShareRate> rates,
			List<PaidSettlement> payouts) {
		TeacherIncomeCriteria criteria = new TeacherIncomeCriteria(teachers.stream().map(TeacherRef::id).toList(),
				null, PeriodMode.DATES, null, LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 31));
		TeacherIncomeReport report = TeacherIncomeCalculator.calculate(new TeacherIncomeCalculator.Input(criteria,
				ZONE, GENERATED, "ශ්‍රී විද්‍යා Institute of Learning (Pvt) Ltd", teachers, entries, List.of(),
				teacherByCourse, names, rates, payouts, "LKR"));
		return report.withRun(UUID.randomUUID(), GENERATED.plusSeconds(900));
	}

	/**
	 * Three teachers (Sinhala, Tamil and Latin names), two currencies:
	 * <ul>
	 * <li>T1 75%: course A paid twice (4,100 = 4,000 + facility fee 100), course B a
	 * refund-only row (-500, count 0), a USD payment on A; PAID payouts 5,000 +
	 * PAID adjustment 10.50 (LKR).</li>
	 * <li>T2 40% from 15 Mar: course C paid 5 Mar (unrated) and 20 Mar (rated) -
	 * a partially unrated row; a USD payout with no USD revenue.</li>
	 * <li>T3 no rate: course D fully unrated (teacher/commission null).</li>
	 * </ul>
	 */
	static TeacherIncomeReport mixed() {
		UUID a = UUID.fromString("20000000-0000-0000-0000-00000000000a");
		UUID b = UUID.fromString("20000000-0000-0000-0000-00000000000b");
		UUID c = UUID.fromString("20000000-0000-0000-0000-00000000000c");
		UUID dd = UUID.fromString("20000000-0000-0000-0000-00000000000d");
		List<TeacherRef> teachers = List.of(new TeacherRef(T1, "කමලා පෙරේරා (Kamala Perera)"),
				new TeacherRef(T2, "செல்வி ராஜேஸ்வரி"), new TeacherRef(T3, "Nimal Fernando"));
		Map<UUID, UUID> teacherByCourse = Map.of(a, T1, b, T1, c, T2, dd, T3);
		Map<UUID, String> names = Map.of(a, "සංයුක්ත ගණිතය 2026 - A/L Combined Mathematics (Theory)", b,
				"Accounting Revision", c, "இயற்பியல் - Physics", dd, "Biology");
		List<ShareRate> rates = List.of(new ShareRate(T1, d("75.00"), LocalDate.of(2025, 1, 1)),
				new ShareRate(T2, d("40.00"), LocalDate.of(2025, 3, 15)));
		List<ReportLedgerEntry> entries = List.of(
				entry(false, "4100.00", "2025-03-03T04:00:00Z", "LKR", PaymentMethod.GATEWAY, course(a, "4000.00")),
				entry(false, "4100.00", "2025-03-04T04:00:00Z", "LKR", PaymentMethod.MANUAL_SLIP,
						course(a, "4000.00")),
				entry(true, "-500.00", "2025-03-06T04:00:00Z", "LKR", PaymentMethod.GATEWAY, course(b, "-500.00")),
				entry(false, "120.00", "2025-03-07T04:00:00Z", "USD", PaymentMethod.GATEWAY, course(a, "100.00")),
				entry(false, "3000.00", "2025-03-05T04:00:00Z", "LKR", PaymentMethod.GATEWAY, course(c, "3000.00")),
				entry(false, "3000.00", "2025-03-20T04:00:00Z", "LKR", PaymentMethod.MANUAL_RECEIPT,
						course(c, "3000.00")),
				entry(false, "2000.00", "2025-03-21T04:00:00Z", "LKR", PaymentMethod.GATEWAY, course(dd, "2000.00")));
		List<PaidSettlement> payouts = List.of(
				new PaidSettlement(UUID.randomUUID(), T1, "REGULAR", d("5000.00"), "LKR",
						Instant.parse("2025-03-31T10:00:00Z")),
				new PaidSettlement(UUID.randomUUID(), T1, "ADJUSTMENT", d("10.50"), "LKR",
						Instant.parse("2025-03-31T11:00:00Z")),
				new PaidSettlement(UUID.randomUUID(), T2, "REGULAR", d("50.00"), "USD",
						Instant.parse("2025-03-31T11:00:00Z")));
		return calculate(teachers, entries, teacherByCourse, names, rates, payouts);
	}

	/** Six teachers x 30 courses with long names - many pages. */
	static TeacherIncomeReport manyPages() {
		List<TeacherRef> teachers = new ArrayList<>();
		Map<UUID, UUID> teacherByCourse = new HashMap<>();
		Map<UUID, String> names = new HashMap<>();
		List<ShareRate> rates = new ArrayList<>();
		List<ReportLedgerEntry> entries = new ArrayList<>();
		for (int t = 0; t < 6; t++) {
			UUID teacher = UUID.fromString(String.format("30000000-0000-0000-0000-%012d", t));
			teachers.add(new TeacherRef(teacher, "Teacher " + t + " with a deliberately long display name"));
			rates.add(new ShareRate(teacher, d("62.50"), LocalDate.of(2025, 1, 1)));
			for (int c = 0; c < 30; c++) {
				UUID course = UUID.fromString(String.format("40000000-0000-0000-%04d-%012d", t, c));
				teacherByCourse.put(course, teacher);
				names.put(course, String.format("Course %02d-%02d Advanced Level Combined Mathematics Theory and "
						+ "Revision Class with a very long title that wraps", t, c));
				for (int p = 0; p <= c % 3; p++) {
					entries.add(entry(false, "1234.50", "2025-03-1" + p + "T04:00:00Z", "LKR",
							PaymentMethod.GATEWAY, course(course, "1234.50")));
				}
			}
		}
		return calculate(teachers, entries, teacherByCourse, names, rates, List.of());
	}

}
