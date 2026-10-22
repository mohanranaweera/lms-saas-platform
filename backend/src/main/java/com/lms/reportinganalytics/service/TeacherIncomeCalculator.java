package com.lms.reportinganalytics.service;

import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry.ReportCourseAllocation;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry.ReportLineAllocation;
import com.lms.ledgersettlementmanagement.api.TeacherIncomeLedgerApi.PaidSettlement;
import com.lms.ledgersettlementmanagement.api.TeacherIncomeLedgerApi.ShareRate;
import com.lms.reportinganalytics.service.TeacherIncomeCriteria.PeriodMode;
import com.lms.reportinganalytics.service.TeacherIncomeReport.CountAmount;
import com.lms.reportinganalytics.service.TeacherIncomeReport.CourseRow;
import com.lms.reportinganalytics.service.TeacherIncomeReport.CurrencyReconciliation;
import com.lms.reportinganalytics.service.TeacherIncomeReport.Filters;
import com.lms.reportinganalytics.service.TeacherIncomeReport.Institute;
import com.lms.reportinganalytics.service.TeacherIncomeReport.Reconciliation;
import com.lms.reportinganalytics.service.TeacherIncomeReport.Section;
import com.lms.reportinganalytics.service.TeacherIncomeReport.TeacherBlock;
import com.lms.reportinganalytics.service.TeacherIncomeReport.TeacherRef;
import com.lms.reportinganalytics.service.TeacherIncomeReport.Totals;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * RPT-01 metric computation (spec sections 2, 2-R, 3) - pure and deterministic, so
 * every financial rule is unit-testable without a database. It only reads the
 * immutable inputs it is given and never writes anything.
 *
 * <ul>
 * <li>Grain: (ledger entry, order line) allocations from {@code
 * RevenueAllocator} (ADR-029 A2 basis), summed per bucket (currency, teacher,
 * course, rate).</li>
 * <li>Teacher income per bucket = {@code round(revenue x rate / 100, 2,
 * HALF_UP)}; commission = revenue - teacher income, so they always reconcile.
 * Rate: a settled allocation's stored {@code share_percent}; otherwise the
 * teacher's rate effective on the entry's tenant-local date; never the current
 * rate. No rate -> revenue counted, {@code rateMissing}, {@code unratedRevenue}.</li>
 * <li>Attribution: settled -> the settlement's teacher; else the course's current
 * primary teacher. Strict teacher filter.</li>
 * <li>FREE / STAFF_GRANTED are non-cash: excluded from revenue and counts, reported
 * in reconciliation.</li>
 * <li>Payouts: stored PAID settlement amounts only (already windowed by the
 * caller); the payment-method filter does not apply to them.</li>
 * </ul>
 */
public final class TeacherIncomeCalculator {

	static final BigDecimal HUNDRED = new BigDecimal("100");

	static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

	private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

	private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

	private static final Set<PaymentMethod> NON_CASH = Set.of(PaymentMethod.FREE, PaymentMethod.STAFF_GRANTED);

	/**
	 * Everything the computation needs.
	 *
	 * @param entries DATES: entries created in the window; MONTHS: entries of orders
	 * with a line in the selected months (any date).
	 * @param monthWindowEntries MONTHS only: entries created inside the selected
	 * months, used only to find non-month-attributable revenue (legacy orders,
	 * MODULE lines, month-less lessons); empty for DATES.
	 * @param currentTeacherByCourse {@code course.teacher_id} today (unsettled attribution).
	 * @param fallbackCurrency used only for an entry whose payment row is missing.
	 */
	public record Input(TeacherIncomeCriteria criteria, ZoneId zone, Instant generatedAt, String instituteName,
			List<TeacherRef> teachers, List<ReportLedgerEntry> entries, List<ReportLedgerEntry> monthWindowEntries,
			Map<UUID, UUID> currentTeacherByCourse, Map<UUID, String> courseNames, List<ShareRate> rates,
			List<PaidSettlement> payouts, String fallbackCurrency) {

	}

	private TeacherIncomeCalculator() {
	}

	public static TeacherIncomeReport calculate(Input in) {
		return new Run(in).calculate();
	}

	// ------------------------------------------------------------------

	private record BucketKey(String currency, UUID teacherId, UUID courseId, BigDecimal rate) {

	}

	private static final class Bucket {

		BigDecimal revenue = ZERO;

		final Set<UUID> confirmedEntries = new HashSet<>();

	}

	private static final class CurrencyAcc {

		BigDecimal gross = ZERO;

		BigDecimal refunds = ZERO;

		BigDecimal serviceFees = ZERO;

		final Set<UUID> nonCashEntries = new HashSet<>();

		BigDecimal nonCash = ZERO;

		final Set<UUID> notMonthEntries = new HashSet<>();

		BigDecimal notMonth = ZERO;

		BigDecimal settledBasisDifference = ZERO;

		BigDecimal unassigned = ZERO;

		final Map<UUID, BigDecimal> teacherServiceFees = new HashMap<>();

	}

	private static final class Run {

		private final Input in;

		private final Set<UUID> selected;

		private final Map<UUID, List<ShareRate>> ratesByTeacher = new HashMap<>();

		private final Map<BucketKey, Bucket> buckets = new LinkedHashMap<>();

		private final Map<String, CurrencyAcc> currencies = new TreeMap<>();

		Run(Input in) {
			this.in = in;
			this.selected = new HashSet<>(in.criteria().teacherIds());
			for (ShareRate rate : in.rates()) {
				ratesByTeacher.computeIfAbsent(rate.teacherId(), key -> new ArrayList<>()).add(rate);
			}
			ratesByTeacher.values().forEach(list -> list.sort(Comparator.comparing(ShareRate::effectiveFrom)));
		}

		TeacherIncomeReport calculate() {
			for (ReportLedgerEntry entry : in.entries()) {
				accumulate(entry);
			}
			if (in.criteria().periodMode() == PeriodMode.MONTHS) {
				accumulateNotMonthAttributable();
			}
			return assemble();
		}

		private boolean methodMatches(ReportLedgerEntry entry) {
			PaymentMethod filter = in.criteria().paymentMethod();
			return filter == null || filter == entry.method();
		}

		private String currencyOf(ReportLedgerEntry entry) {
			return entry.currency() == null ? in.fallbackCurrency() : entry.currency();
		}

		private CurrencyAcc acc(String currency) {
			return currencies.computeIfAbsent(currency, key -> new CurrencyAcc());
		}

		private UUID teacherOf(ReportCourseAllocation allocation) {
			if (allocation.settlement() != null) {
				return allocation.settlement().teacherId();
			}
			return in.currentTeacherByCourse().get(allocation.courseId());
		}

		/** The in-period part of an allocation: whole (DATES) or its lines billed in a selected month. */
		private BigDecimal scopedAmount(ReportCourseAllocation allocation) {
			if (in.criteria().periodMode() == PeriodMode.DATES) {
				return allocation.amount();
			}
			BigDecimal sum = ZERO;
			for (ReportLineAllocation line : allocation.lines()) {
				if (line.hasBillingMonth()
						&& in.criteria().months().contains(YearMonth.of(line.billingYear(), line.billingMonth()))) {
					sum = sum.add(line.amount());
				}
			}
			return sum;
		}

		private void accumulate(ReportLedgerEntry entry) {
			if (!methodMatches(entry)) {
				return;
			}
			String currency = currencyOf(entry);
			boolean nonCash = entry.method() != null && NON_CASH.contains(entry.method());
			Set<UUID> touchedTeachers = new HashSet<>();
			BigDecimal allocated = ZERO;
			for (ReportCourseAllocation allocation : entry.courses()) {
				allocated = allocated.add(allocation.amount());
				BigDecimal scoped = scopedAmount(allocation);
				if (scoped.signum() == 0) {
					continue;
				}
				UUID teacher = teacherOf(allocation);
				if (teacher == null) {
					if (!nonCash) {
						acc(currency).unassigned = acc(currency).unassigned.add(scoped);
					}
					continue;
				}
				if (!selected.contains(teacher)) {
					continue;
				}
				CurrencyAcc acc = acc(currency);
				if (nonCash) {
					acc.nonCash = acc.nonCash.add(scoped);
					if (!entry.refund()) {
						acc.nonCashEntries.add(entry.entryId());
					}
					continue;
				}
				touchedTeachers.add(teacher);
				BigDecimal rate = allocation.settlement() != null ? allocation.settlement().sharePercent()
						: rateOn(teacher, LocalDate.ofInstant(entry.createdAt(), in.zone()));
				Bucket bucket = buckets.computeIfAbsent(new BucketKey(currency, teacher, allocation.courseId(),
						rate == null ? null : rate.setScale(2, RoundingMode.HALF_UP)), key -> new Bucket());
				bucket.revenue = bucket.revenue.add(scoped);
				if (!entry.refund()) {
					bucket.confirmedEntries.add(entry.entryId());
				}
				if (scoped.signum() > 0) {
					acc.gross = acc.gross.add(scoped);
				}
				else {
					acc.refunds = acc.refunds.add(scoped.negate());
				}
				if (allocation.settlement() != null) {
					acc.settledBasisDifference = acc.settledBasisDifference
						.add(allocation.settlement().itemAmount().subtract(allocation.amount()));
				}
			}
			if (!touchedTeachers.isEmpty()) {
				// Service fees (2-R.2): facility fees + postage = entry - all course
				// allocations, signed; once per entry in the section, and under each
				// selected teacher the entry touches.
				BigDecimal fee = entry.amount().subtract(allocated);
				CurrencyAcc acc = acc(currency);
				acc.serviceFees = acc.serviceFees.add(fee);
				for (UUID teacher : touchedTeachers) {
					acc.teacherServiceFees.merge(teacher, fee, BigDecimal::add);
				}
			}
		}

		/**
		 * MONTHS mode: allocations of the selected teachers that cannot be placed in
		 * a billing month (legacy line-less orders, MODULE lines, month-less
		 * lessons) of entries created in the selected months or belonging to an
		 * order with a line in them. Reported, never guessed into a month.
		 */
		private void accumulateNotMonthAttributable() {
			Map<UUID, ReportLedgerEntry> union = new LinkedHashMap<>();
			in.entries().forEach(entry -> union.put(entry.entryId(), entry));
			in.monthWindowEntries().forEach(entry -> union.putIfAbsent(entry.entryId(), entry));
			for (ReportLedgerEntry entry : union.values()) {
				if (!methodMatches(entry) || (entry.method() != null && NON_CASH.contains(entry.method()))) {
					continue;
				}
				for (ReportCourseAllocation allocation : entry.courses()) {
					UUID teacher = teacherOf(allocation);
					if (teacher == null || !selected.contains(teacher)) {
						continue;
					}
					BigDecimal part = ZERO;
					if (allocation.lines().isEmpty()) {
						if (!entry.lineOrder()) {
							part = allocation.amount();
						}
					}
					else {
						for (ReportLineAllocation line : allocation.lines()) {
							if (!line.hasBillingMonth()) {
								part = part.add(line.amount());
							}
						}
					}
					if (part.signum() != 0) {
						CurrencyAcc acc = acc(currencyOf(entry));
						acc.notMonth = acc.notMonth.add(part);
						if (!entry.refund()) {
							acc.notMonthEntries.add(entry.entryId());
						}
					}
				}
			}
		}

		private BigDecimal rateOn(UUID teacher, LocalDate date) {
			BigDecimal effective = null;
			for (ShareRate rate : ratesByTeacher.getOrDefault(teacher, List.of())) {
				if (!rate.effectiveFrom().isAfter(date)) {
					effective = rate.sharePercent();
				}
			}
			return effective;
		}

		// --------------------------------------------------------------
		// Assembly.
		// --------------------------------------------------------------

		private static final class RowAcc {

			BigDecimal revenue = ZERO;

			BigDecimal teacher = ZERO;

			BigDecimal commission = ZERO;

			BigDecimal unrated = ZERO;

			boolean anyRated;

			boolean anyUnrated;

			final Set<UUID> entries = new HashSet<>();

		}

		private TeacherIncomeReport assemble() {
			Map<String, String> teacherNames = new HashMap<>();
			in.teachers().forEach(ref -> teacherNames.put(ref.id().toString(), ref.name()));

			// currency -> teacher -> course -> row
			Map<String, Map<UUID, Map<UUID, RowAcc>>> rows = new TreeMap<>();
			for (Map.Entry<BucketKey, Bucket> e : buckets.entrySet()) {
				BucketKey key = e.getKey();
				Bucket bucket = e.getValue();
				RowAcc row = rows.computeIfAbsent(key.currency(), k -> new HashMap<>())
					.computeIfAbsent(key.teacherId(), k -> new HashMap<>())
					.computeIfAbsent(key.courseId(), k -> new RowAcc());
				row.revenue = row.revenue.add(bucket.revenue);
				row.entries.addAll(bucket.confirmedEntries);
				if (key.rate() == null) {
					row.anyUnrated = true;
					row.unrated = row.unrated.add(bucket.revenue);
				}
				else {
					BigDecimal teacher = bucket.revenue.multiply(key.rate()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
					row.anyRated = true;
					row.teacher = row.teacher.add(teacher);
					row.commission = row.commission.add(bucket.revenue.subtract(teacher));
				}
			}

			// Payouts: stored PAID settlement figures per (currency, teacher).
			Map<String, Map<UUID, BigDecimal>> payouts = new TreeMap<>();
			for (PaidSettlement paid : in.payouts()) {
				if (!selected.contains(paid.teacherId())) {
					continue;
				}
				payouts.computeIfAbsent(paid.currency(), k -> new HashMap<>())
					.merge(paid.teacherId(), paid.shareAmount(), BigDecimal::add);
			}

			List<String> payoutOnlyNotes = new ArrayList<>();
			Set<String> sectionCurrencies = new TreeSet<>(rows.keySet());
			sectionCurrencies.addAll(payouts.keySet());
			List<Section> sections = new ArrayList<>();
			for (String currency : sectionCurrencies) {
				Map<UUID, Map<UUID, RowAcc>> byTeacher = rows.getOrDefault(currency, Map.of());
				Map<UUID, BigDecimal> payoutsByTeacher = payouts.getOrDefault(currency, Map.of());
				Set<UUID> teacherIds = new HashSet<>(byTeacher.keySet());
				payoutsByTeacher.forEach((teacher, amount) -> teacherIds.add(teacher));
				CurrencyAcc acc = currencies.getOrDefault(currency, new CurrencyAcc());

				List<TeacherBlock> blocks = new ArrayList<>();
				BigDecimal sRevenue = ZERO;
				BigDecimal sTeacher = ZERO;
				BigDecimal sCommission = ZERO;
				BigDecimal sPayouts = ZERO;
				BigDecimal sUnrated = ZERO;
				Set<UUID> sEntries = new HashSet<>();
				for (UUID teacherId : teacherIds) {
					Map<UUID, RowAcc> courses = byTeacher.getOrDefault(teacherId, Map.of());
					List<CourseRow> courseRows = new ArrayList<>();
					BigDecimal tRevenue = ZERO;
					BigDecimal tTeacher = ZERO;
					BigDecimal tCommission = ZERO;
					BigDecimal tUnrated = ZERO;
					Set<UUID> tEntries = new HashSet<>();
					for (Map.Entry<UUID, RowAcc> c : courses.entrySet()) {
						RowAcc row = c.getValue();
						courseRows.add(new CourseRow(c.getKey(), courseName(c.getKey()), row.entries.size(),
								money(row.revenue), row.anyRated ? money(row.teacher) : null,
								row.anyRated ? money(row.commission) : null, row.anyUnrated));
						tRevenue = tRevenue.add(row.revenue);
						tTeacher = tTeacher.add(row.teacher);
						tCommission = tCommission.add(row.commission);
						tUnrated = tUnrated.add(row.unrated);
						tEntries.addAll(row.entries);
					}
					courseRows.sort(Comparator.comparing(CourseRow::courseName, String.CASE_INSENSITIVE_ORDER)
						.thenComparing(CourseRow::courseName)
						.thenComparing(CourseRow::courseId));
					BigDecimal tPayouts = payoutsByTeacher.getOrDefault(teacherId, ZERO);
					if (courseRows.isEmpty() && tPayouts.signum() != 0) {
						payoutOnlyNotes.add(currency + ": " + teacherName(teacherNames, teacherId) + " has payouts of "
								+ money(tPayouts) + " in " + currency
								+ " but no revenue in this currency in this report (payouts are shown in the stored "
								+ "settlement currency).");
					}
					BigDecimal tFees = acc.teacherServiceFees.getOrDefault(teacherId, ZERO);
					Totals totals = new Totals(money(tRevenue), money(tTeacher), money(tCommission), money(tFees),
							money(tPayouts), null, money(tTeacher.subtract(tPayouts)), tEntries.size(), 1,
							money(tUnrated));
					blocks.add(new TeacherBlock(teacherId, teacherName(teacherNames, teacherId), totals,
							List.copyOf(courseRows)));
					sRevenue = sRevenue.add(tRevenue);
					sTeacher = sTeacher.add(tTeacher);
					sCommission = sCommission.add(tCommission);
					sPayouts = sPayouts.add(tPayouts);
					sUnrated = sUnrated.add(tUnrated);
					sEntries.addAll(tEntries);
				}
				blocks.sort(Comparator.comparing(TeacherBlock::teacherName, String.CASE_INSENSITIVE_ORDER)
					.thenComparing(TeacherBlock::teacherName)
					.thenComparing(TeacherBlock::teacherId));
				Totals sectionTotals = new Totals(money(sRevenue), money(sTeacher), money(sCommission),
						money(acc.serviceFees), money(sPayouts), null, money(sTeacher.subtract(sPayouts)),
						sEntries.size(), blocks.size(), money(sUnrated));
				sections.add(new Section(currency, sectionTotals, List.copyOf(blocks)));
			}

			List<CurrencyReconciliation> byCurrency = new ArrayList<>();
			List<String> notes = new ArrayList<>();
			notes.add("Teacher share (earned) is the teacher's earned share of this report's revenue, "
					+ "not cash paid out.");
			notes.add("Payouts are stored PAID teacher settlements whose paid date falls in the selected period; "
					+ "the payment method filter does not apply to payouts. Balance = teacher share - payouts "
					+ "(a period movement, not an account balance). Payins are not tracked.");
			notes.add("Facility fees and postage are institute-only service fees and are not part of revenue, "
					+ "teacher share or commission. A service fee of an order shared by several selected teachers "
					+ "is shown under each of them but counted once in the totals.");
			notes.add("Unsettled revenue uses the course's current teacher and the teacher's rate effective on "
					+ "the payment date, as of the generation time.");
			for (Map.Entry<String, CurrencyAcc> e : currencies.entrySet()) {
				String currency = e.getKey();
				CurrencyAcc acc = e.getValue();
				byCurrency.add(new CurrencyReconciliation(currency, money(acc.gross), money(acc.refunds),
						new CountAmount(acc.nonCashEntries.size(), money(acc.nonCash)),
						new CountAmount(acc.notMonthEntries.size(), money(acc.notMonth)),
						money(acc.settledBasisDifference), money(acc.unassigned)));
				if (acc.settledBasisDifference.signum() != 0) {
					notes.add(currency + ": already-settled revenue differs from this report's basis by "
							+ money(acc.settledBasisDifference)
							+ " (settled before facility fees and postage were excluded from course shares).");
				}
				if (acc.nonCash.signum() != 0 || !acc.nonCashEntries.isEmpty()) {
					notes.add(currency + ": " + acc.nonCashEntries.size()
							+ " complimentary (free / staff-granted) payment(s) worth " + money(acc.nonCash)
							+ " are excluded (no cash received).");
				}
				if (acc.notMonth.signum() != 0) {
					notes.add(currency + ": " + money(acc.notMonth)
							+ " cannot be placed in a billing month (legacy, module or undated lesson purchases) "
							+ "and is excluded from the month view.");
				}
				if (acc.unassigned.signum() != 0) {
					notes.add(currency + ": " + money(acc.unassigned)
							+ " belongs to courses with no assigned teacher and is not shown.");
				}
			}
			for (Section section : sections) {
				if (new BigDecimal(section.totals().unratedRevenue()).signum() != 0) {
					notes.add("* " + section.currency() + ": " + section.totals().unratedRevenue()
							+ " of revenue has no effective teacher share rate; courses marked * exclude that part "
							+ "from teacher share and commission (\u2014 = no rated part).");
				}
			}
			notes.addAll(payoutOnlyNotes);

			TeacherIncomeCriteria c = in.criteria();
			List<String> months = c.months() == null ? null : c.months().stream().map(YearMonth::toString).toList();
			Filters filters = new Filters(List.copyOf(in.teachers()),
					c.paymentMethod() == null ? null : c.paymentMethod().name(), c.periodMode().name(), months,
					c.startDate(), c.endDate(), periodLabel(c));
			return new TeacherIncomeReport(null, null, in.generatedAt(), in.zone().getId(),
					new Institute(in.instituteName()), filters, List.copyOf(sections),
					new Reconciliation(List.copyOf(byCurrency), List.copyOf(notes)));
		}

		private String courseName(UUID courseId) {
			String name = in.courseNames().get(courseId);
			return name == null ? "Unknown course" : name;
		}

		private static String teacherName(Map<String, String> names, UUID teacherId) {
			String name = names.get(teacherId.toString());
			return name == null ? "Unknown teacher" : name;
		}

	}

	static String periodLabel(TeacherIncomeCriteria c) {
		if (c.periodMode() == PeriodMode.MONTHS) {
			List<String> labels = new ArrayList<>();
			c.months().forEach(month -> labels.add(month.format(MONTH_LABEL)));
			return String.join(", ", labels);
		}
		return c.startDate().format(DATE_LABEL) + " to " + c.endDate().format(DATE_LABEL);
	}

	static String money(BigDecimal amount) {
		return Objects.requireNonNullElse(amount, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP).toPlainString();
	}

}
