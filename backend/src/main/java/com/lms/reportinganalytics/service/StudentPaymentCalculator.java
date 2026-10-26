package com.lms.reportinganalytics.service;

import com.lms.ledgersettlementmanagement.api.InstantRange;
import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry.ReportCourseAllocation;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry.ReportLineAllocation;
import com.lms.paymentmanagement.api.ManualReceiptFact;
import com.lms.paymentmanagement.api.OrderCourseCharge;
import com.lms.paymentmanagement.api.OrderLineSnapshot;
import com.lms.reportinganalytics.service.StudentPaymentReport.CountAmount;
import com.lms.reportinganalytics.service.StudentPaymentReport.CourseRef;
import com.lms.reportinganalytics.service.StudentPaymentReport.CurrencyReconciliation;
import com.lms.reportinganalytics.service.StudentPaymentReport.Filters;
import com.lms.reportinganalytics.service.StudentPaymentReport.Institute;
import com.lms.reportinganalytics.service.StudentPaymentReport.MethodTotal;
import com.lms.reportinganalytics.service.StudentPaymentReport.Reconciliation;
import com.lms.reportinganalytics.service.StudentPaymentReport.Section;
import com.lms.reportinganalytics.service.StudentPaymentReport.Summary;
import com.lms.reportinganalytics.service.support.ReportPeriodRules;
import com.lms.reportinganalytics.service.support.ReportPeriodRules.Mode;
import com.lms.reportinganalytics.service.support.ReportPeriodRules.Period;
import com.lms.usermanagement.api.StudentIndexEntry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * RPT-02 Student Payment Report computation (spec sections 3, 3-R, 4) - pure
 * and deterministic: it only reads the immutable inputs it is given (ledger
 * entries with their ADR-029 + A2 course/line allocations, the order line and
 * course-charge snapshots, manual receipts) and never writes anything.
 *
 * <ul>
 * <li>Grain: one row per (entry, course, order line); legacy line-less orders
 * one row per (entry, course). Receipts are never merged; the order total is
 * never repeated.</li>
 * <li>{@code amount} = the line's course allocation (net of discounts, signed).
 * The ancillary part {@code entry - sum(course allocations)} is split over the
 * snapshot parts (each line's {@code facility_fee_amount}, each course's
 * {@code postage_amount}) pro rata, remainder to the largest part (ties: lowest
 * line_no, postage last), so fees + postage of an entry's rows equal its
 * ancillary exactly and a staff-adjusted / refunded entry is scaled the same
 * way as its course parts (3-R.1). Never recomputed from current settings.</li>
 * <li>Scope: course filter at allocation level; MONTHS by line billing month
 * (any selected month); DATES by the payment date (manual receipt: {@code
 * received_on}; others and all refunds: ledger time). Rows that are zero in
 * amount, fee and postage are omitted (3-R.2). FREE / STAFF_GRANTED are
 * non-cash: reconciled, never rows.</li>
 * </ul>
 */
public final class StudentPaymentCalculator {

	static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

	private static final Set<PaymentMethod> NON_CASH = Set.of(PaymentMethod.FREE, PaymentMethod.STAFF_GRANTED);

	/**
	 * Everything the computation needs (all already tenant-scoped by the caller).
	 *
	 * @param selectedCourses the validated course filter with names (empty = all courses).
	 * @param entries DATES: entries created in the window plus manual-receipt
	 * confirmations received in the date range; MONTHS: entries of orders with a
	 * line in the selected months. Duplicates (same entry id) are ignored.
	 * @param studentByOrder {@code student_order.student_id} per order.
	 * @param lessonMonths current billing month of LESSON lines' lessons.
	 * @param fallbackCurrency used only for an entry whose payment row is missing.
	 */
	public record Input(StudentPaymentCriteria criteria, ZoneId zone, Instant generatedAt, String instituteName,
			List<CourseRef> selectedCourses, List<ReportLedgerEntry> entries, List<OrderLineSnapshot> orderLines,
			List<OrderCourseCharge> courseCharges, Map<UUID, UUID> studentByOrder, List<ManualReceiptFact> receipts,
			Map<UUID, YearMonth> lessonMonths, Map<UUID, String> courseNames, List<StudentIndexEntry> students,
			String fallbackCurrency) {

	}

	public record Result(StudentPaymentReport report, List<StudentPaymentRow> rows) {

	}

	private StudentPaymentCalculator() {
	}

	public static Result calculate(Input in) {
		return new Run(in).calculate();
	}

	// ------------------------------------------------------------------

	/** One candidate row of an entry before scoping. */
	private static final class Draft {

		final UUID courseId;

		final int lineNo;

		final String scope;

		final YearMonth billingMonth;

		final String reason;

		BigDecimal amount;

		BigDecimal fee = ZERO;

		BigDecimal postage = ZERO;

		Draft(UUID courseId, int lineNo, String scope, YearMonth billingMonth, String reason, BigDecimal amount) {
			this.courseId = courseId;
			this.lineNo = lineNo;
			this.scope = scope;
			this.billingMonth = billingMonth;
			this.reason = reason;
			this.amount = amount;
		}

		boolean allZero() {
			return amount.signum() == 0 && fee.signum() == 0 && postage.signum() == 0;
		}

	}

	/** One snapshot part the ancillary amount is split over. */
	private record Part(Draft target, boolean postage, int lineNo, BigDecimal snapshot) {

	}

	record MethodKey(String method, boolean manual) {

	}

	private static final class MethodAcc {

		BigDecimal total = ZERO;

		final Set<UUID> receipts = new HashSet<>();

	}

	private static final class CurrencyAcc {

		final Set<UUID> students = new HashSet<>();

		final Set<UUID> receipts = new HashSet<>();

		final Set<UUID> refunds = new HashSet<>();

		long rows;

		BigDecimal course = ZERO;

		BigDecimal fees = ZERO;

		BigDecimal postage = ZERO;

		BigDecimal gross = ZERO;

		BigDecimal refunded = ZERO;

		final Map<MethodKey, MethodAcc> methods = new HashMap<>();

		final Set<UUID> adjustedReceipts = new HashSet<>();

		BigDecimal adjustments = ZERO;

		final Set<UUID> nonCashEntries = new HashSet<>();

		BigDecimal nonCash = ZERO;

		final Set<UUID> notMonthEntries = new HashSet<>();

		BigDecimal notMonth = ZERO;

	}

	private static final class Run {

		private final Input in;

		private final Period period;

		private final Set<UUID> courseFilter;

		private final Map<UUID, List<OrderLineSnapshot>> linesByOrder = new HashMap<>();

		private final Map<UUID, Map<UUID, BigDecimal>> postageByOrder = new HashMap<>();

		private final Map<UUID, ManualReceiptFact> receiptByPayment = new HashMap<>();

		private final Map<UUID, StudentIndexEntry> studentById = new HashMap<>();

		private final Map<String, CurrencyAcc> currencies = new TreeMap<>();

		private final List<StudentPaymentRow> rows = new ArrayList<>();

		private final InstantRange window;

		Run(Input in) {
			this.in = in;
			this.period = in.criteria().period();
			this.courseFilter = Set.copyOf(in.criteria().courseIds());
			for (OrderLineSnapshot line : in.orderLines()) {
				linesByOrder.computeIfAbsent(line.orderId(), key -> new ArrayList<>()).add(line);
			}
			linesByOrder.values().forEach(lines -> lines.sort(Comparator.comparingInt(OrderLineSnapshot::lineNo)));
			for (OrderCourseCharge charge : in.courseCharges()) {
				if (charge.postageAmount() != null && charge.postageAmount().signum() > 0) {
					postageByOrder.computeIfAbsent(charge.orderId(), key -> new LinkedHashMap<>())
						.merge(charge.courseId(), charge.postageAmount(), BigDecimal::add);
				}
			}
			in.receipts().forEach(receipt -> receiptByPayment.put(receipt.paymentId(), receipt));
			in.students().forEach(student -> studentById.put(student.userId(), student));
			this.window = period.mode() == Mode.DATES
					? ReportPeriodRules.dateWindow(period.startDate(), period.endDate(), in.zone()) : null;
		}

		Result calculate() {
			Set<UUID> seen = new HashSet<>();
			for (ReportLedgerEntry entry : in.entries()) {
				if (seen.add(entry.entryId())) {
					accumulate(entry);
				}
			}
			rows.sort(Comparator.comparing(StudentPaymentRow::currency)
				.thenComparing(StudentPaymentRow::sortAt)
				.thenComparing(StudentPaymentRow::entryId)
				.thenComparingInt(StudentPaymentRow::lineNo)
				.thenComparing(StudentPaymentRow::courseId, Comparator.nullsFirst(Comparator.naturalOrder())));
			return new Result(assemble(), List.copyOf(rows));
		}

		private String currencyOf(ReportLedgerEntry entry) {
			return entry.currency() == null ? in.fallbackCurrency() : entry.currency();
		}

		private CurrencyAcc acc(String currency) {
			return currencies.computeIfAbsent(currency, key -> new CurrencyAcc());
		}

		private void accumulate(ReportLedgerEntry entry) {
			PaymentMethod filter = in.criteria().paymentMethod();
			if (filter != null && filter != entry.method()) {
				return;
			}
			ManualReceiptFact receipt = entry.method() == PaymentMethod.MANUAL_RECEIPT && entry.paymentId() != null
					? receiptByPayment.get(entry.paymentId()) : null;
			boolean dateOnly = receipt != null && !entry.refund();
			if (period.mode() == Mode.DATES) {
				boolean inScope = dateOnly
						? !receipt.receivedOn().isBefore(period.startDate())
								&& !receipt.receivedOn().isAfter(period.endDate())
						: window.contains(entry.createdAt());
				if (!inScope) {
					return;
				}
			}
			String currency = currencyOf(entry);
			boolean nonCash = entry.method() != null && NON_CASH.contains(entry.method());

			List<Draft> scoped = new ArrayList<>();
			BigDecimal notMonth = ZERO;
			for (Draft draft : drafts(entry)) {
				if (!courseFilter.isEmpty() && !courseFilter.contains(draft.courseId)) {
					continue;
				}
				if (period.mode() == Mode.MONTHS) {
					if (draft.billingMonth == null) {
						notMonth = notMonth.add(draft.amount);
						continue;
					}
					if (!period.months().contains(draft.billingMonth)) {
						continue;
					}
				}
				scoped.add(draft);
			}
			if (!nonCash && notMonth.signum() != 0) {
				CurrencyAcc acc = acc(currency);
				acc.notMonth = acc.notMonth.add(notMonth);
				if (!entry.refund()) {
					acc.notMonthEntries.add(entry.entryId());
				}
			}
			scoped.removeIf(Draft::allZero);
			if (scoped.isEmpty()) {
				return;
			}
			CurrencyAcc acc = acc(currency);
			if (nonCash) {
				for (Draft draft : scoped) {
					acc.nonCash = acc.nonCash.add(draft.amount);
				}
				if (!entry.refund()) {
					acc.nonCashEntries.add(entry.entryId());
				}
				return;
			}

			MethodKey method = displayMethod(entry, receiptByPayment.get(entry.paymentId()));
			MethodAcc methodAcc = acc.methods.computeIfAbsent(method, key -> new MethodAcc());
			UUID studentId = studentOf(entry);
			StudentIndexEntry student = studentId == null ? null : studentById.get(studentId);
			String paymentDate = dateOnly ? receipt.receivedOn().toString() : entry.createdAt().toString();
			Instant sortAt = dateOnly ? receipt.receivedOn().atStartOfDay(in.zone()).toInstant() : entry.createdAt();
			for (Draft draft : scoped) {
				rows.add(new StudentPaymentRow(currency, entry.entryId(), entry.paymentId(), entry.refund(), studentId,
						studentName(student), student == null || student.indexNumber() == null ? ""
								: student.indexNumber(),
						draft.courseId, courseName(draft.courseId), draft.reason, draft.scope, method.method(),
						method.manual(), paymentDate, dateOnly, entry.createdAt(), money(draft.amount),
						money(draft.fee), money(draft.postage), draft.lineNo, sortAt));
				acc.rows++;
				acc.course = acc.course.add(draft.amount);
				acc.fees = acc.fees.add(draft.fee);
				acc.postage = acc.postage.add(draft.postage);
				methodAcc.total = methodAcc.total.add(draft.amount);
				if (entry.refund()) {
					acc.refunded = acc.refunded.add(draft.amount.negate());
				}
				else {
					acc.gross = acc.gross.add(draft.amount);
				}
			}
			if (entry.refund()) {
				acc.refunds.add(entry.entryId());
			}
			else {
				acc.receipts.add(entry.entryId());
				methodAcc.receipts.add(entry.entryId());
				if (studentId != null) {
					acc.students.add(studentId);
				}
				if (receipt != null && receipt.staffAdjustmentAmount() != null
						&& receipt.staffAdjustmentAmount().signum() > 0) {
					acc.adjustedReceipts.add(entry.entryId());
					acc.adjustments = acc.adjustments.add(filteredAdjustment(receipt, scoped, entry.amount()));
				}
			}
		}

		/**
		 * Allocate a receipt adjustment over the immutable selected parts. Course
		 * and month filters can retain only part of one receipt, so reconciliation
		 * must not claim the full receipt adjustment for a partial result.
		 */
		private static BigDecimal filteredAdjustment(ManualReceiptFact receipt, List<Draft> scoped,
				BigDecimal entryAmount) {
			if (entryAmount == null || entryAmount.signum() <= 0) {
				return ZERO;
			}
			BigDecimal included = ZERO;
			for (Draft draft : scoped) {
				included = included.add(draft.amount).add(draft.fee).add(draft.postage);
			}
			if (included.signum() <= 0) {
				return ZERO;
			}
			return receipt.staffAdjustmentAmount().multiply(included).divide(entryAmount, 2, RoundingMode.HALF_UP);
		}

		private UUID studentOf(ReportLedgerEntry entry) {
			UUID student = in.studentByOrder().get(entry.orderId());
			if (student != null) {
				return student;
			}
			List<OrderLineSnapshot> lines = linesByOrder.get(entry.orderId());
			return lines == null || lines.isEmpty() ? null : lines.get(0).studentId();
		}

		// --------------------------------------------------------------
		// Row drafts of one entry (unscoped): course parts + ancillary split.
		// --------------------------------------------------------------

		private List<Draft> drafts(ReportLedgerEntry entry) {
			List<OrderLineSnapshot> lines = linesByOrder.getOrDefault(entry.orderId(), List.of());
			BigDecimal allocated = ZERO;
			for (ReportCourseAllocation allocation : entry.courses()) {
				allocated = allocated.add(allocation.amount());
			}
			BigDecimal ancillary = entry.amount().subtract(allocated);
			List<Draft> drafts = new ArrayList<>();
			if (!entry.lineOrder() || lines.isEmpty()) {
				for (ReportCourseAllocation allocation : entry.courses()) {
					drafts.add(legacy(allocation.courseId(), allocation.amount()));
				}
				if (ancillary.signum() != 0 && !drafts.isEmpty()) {
					drafts.get(0).fee = drafts.get(0).fee.add(ancillary);
				}
				return drafts;
			}

			Map<UUID, BigDecimal> byLine = new HashMap<>();
			for (ReportCourseAllocation allocation : entry.courses()) {
				for (ReportLineAllocation line : allocation.lines()) {
					byLine.merge(line.lineId(), line.amount(), BigDecimal::add);
				}
			}
			Map<UUID, Draft> lowestByCourse = new LinkedHashMap<>();
			for (OrderLineSnapshot line : lines) {
				Draft draft = lineDraft(line, byLine.getOrDefault(line.lineId(), ZERO));
				drafts.add(draft);
				lowestByCourse.putIfAbsent(line.courseId(), draft);
			}
			// Defensive: a course allocation not fully split to its lines keeps the
			// remainder on the course's lowest line (or a course-level row).
			for (ReportCourseAllocation allocation : entry.courses()) {
				BigDecimal onLines = ZERO;
				for (Draft draft : drafts) {
					if (draft.courseId.equals(allocation.courseId())) {
						onLines = onLines.add(draft.amount);
					}
				}
				BigDecimal residual = allocation.amount().subtract(onLines);
				if (residual.signum() != 0) {
					Draft target = lowestByCourse.get(allocation.courseId());
					if (target == null) {
						drafts.add(legacy(allocation.courseId(), residual));
					}
					else {
						target.amount = target.amount.add(residual);
					}
				}
			}

			List<Part> parts = new ArrayList<>();
			for (int i = 0; i < lines.size(); i++) {
				OrderLineSnapshot line = lines.get(i);
				BigDecimal fee = line.facilityFee() == null ? ZERO : line.facilityFee();
				parts.add(new Part(drafts.get(i), false, line.lineNo(), fee));
			}
			for (Map.Entry<UUID, BigDecimal> postage : postageByOrder.getOrDefault(entry.orderId(), Map.of())
				.entrySet()) {
				Draft target = lowestByCourse.getOrDefault(postage.getKey(), drafts.get(0));
				parts.add(new Part(target, true, target.lineNo, postage.getValue()));
			}
			splitAncillary(ancillary, parts, drafts.get(0));
			return drafts;
		}

		private Draft lineDraft(OrderLineSnapshot line, BigDecimal amount) {
			YearMonth month = null;
			String reason;
			if ("MONTHLY".equals(line.scope())) {
				if (line.periodYear() != null && line.periodMonth() != null) {
					month = YearMonth.of(line.periodYear(), line.periodMonth());
				}
				reason = month == null ? label(line.label(), "Monthly fee") : month.toString();
			}
			else if ("LESSON".equals(line.scope())) {
				month = line.lessonId() == null ? null : in.lessonMonths().get(line.lessonId());
				reason = "Session: " + label(line.label(), "session");
			}
			else if ("MODULE".equals(line.scope())) {
				reason = "Module: " + label(line.label(), "module");
			}
			else {
				reason = label(line.label(), "Course payment");
			}
			return new Draft(line.courseId(), line.lineNo(), line.scope(), month, reason, amount);
		}

		private static Draft legacy(UUID courseId, BigDecimal amount) {
			return new Draft(courseId, 0, "LEGACY", null, "Course payment", amount);
		}

		private static String label(String value, String fallback) {
			return value == null || value.isBlank() ? fallback : value.trim();
		}

		/**
		 * Pro rata split of the entry's ancillary amount over the snapshot parts:
		 * {@code |ancillary| x part / sum(parts)} (2 dp, HALF_UP), the rounding
		 * remainder to the largest part (first in line order; fee parts precede
		 * postage parts, so ties go to the lowest line_no and postage last), the
		 * sign of the ancillary kept. With no positive snapshot the whole amount
		 * stays on the first line's fee (defensive only).
		 */
		private static void splitAncillary(BigDecimal ancillary, List<Part> parts, Draft first) {
			if (ancillary.signum() == 0) {
				return;
			}
			BigDecimal total = ZERO;
			for (Part part : parts) {
				total = total.add(part.snapshot());
			}
			if (total.signum() <= 0) {
				first.fee = first.fee.add(ancillary);
				return;
			}
			BigDecimal magnitude = ancillary.abs();
			List<BigDecimal> shares = new ArrayList<>();
			BigDecimal assigned = ZERO;
			int target = 0;
			for (int i = 0; i < parts.size(); i++) {
				BigDecimal share = magnitude.multiply(parts.get(i).snapshot()).divide(total, 2, RoundingMode.HALF_UP);
				shares.add(share);
				assigned = assigned.add(share);
				if (parts.get(i).snapshot().compareTo(parts.get(target).snapshot()) > 0) {
					target = i;
				}
			}
			shares.set(target, shares.get(target).add(magnitude.subtract(assigned)));
			for (int i = 0; i < parts.size(); i++) {
				BigDecimal signed = ancillary.signum() < 0 ? shares.get(i).negate() : shares.get(i);
				Part part = parts.get(i);
				if (part.postage()) {
					part.target().postage = part.target().postage.add(signed);
				}
				else {
					part.target().fee = part.target().fee.add(signed);
				}
			}
		}

		// --------------------------------------------------------------
		// Assembly.
		// --------------------------------------------------------------

		private StudentPaymentReport assemble() {
			List<Section> sections = new ArrayList<>();
			List<CurrencyReconciliation> byCurrency = new ArrayList<>();
			List<String> notes = new ArrayList<>();
			int rowCount = 0;
			for (Map.Entry<String, CurrencyAcc> e : currencies.entrySet()) {
				String currency = e.getKey();
				CurrencyAcc acc = e.getValue();
				if (acc.rows > 0) {
					List<Map.Entry<MethodKey, MethodAcc>> methods = new ArrayList<>(acc.methods.entrySet());
					methods.removeIf(m -> m.getValue().receipts.isEmpty() && m.getValue().total.signum() == 0);
					methods.sort(Comparator.comparingInt(m -> methodRank(m.getKey())));
					List<MethodTotal> byMethod = methods.stream()
						.map(m -> new MethodTotal(m.getKey().method(), m.getKey().manual(), money(m.getValue().total),
								m.getValue().receipts.size()))
						.toList();
					sections.add(new Section(currency, new Summary(acc.students.size(), acc.receipts.size(),
							acc.refunds.size(), acc.rows, money(acc.course), byMethod, money(acc.fees),
							money(acc.postage), money(acc.course.add(acc.fees).add(acc.postage)))));
					rowCount += (int) acc.rows;
				}
				byCurrency.add(new CurrencyReconciliation(currency, money(acc.gross), money(acc.refunded),
						new CountAmount(acc.adjustedReceipts.size(), money(acc.adjustments)),
						new CountAmount(acc.nonCashEntries.size(), money(acc.nonCash)),
						new CountAmount(acc.notMonthEntries.size(), money(acc.notMonth))));
				if (acc.adjustments.signum() != 0) {
					notes.add(currency + ": " + acc.adjustedReceipts.size() + " manual receipt(s) were recorded with "
							+ "staff adjustments totalling " + money(acc.adjustments)
							+ "; their course payments, service fees and postage are shown as actually received "
							+ "(scaled pro rata).");
				}
				if (!acc.nonCashEntries.isEmpty() || acc.nonCash.signum() != 0) {
					notes.add(currency + ": " + acc.nonCashEntries.size()
							+ " complimentary (free / staff-granted) payment(s) worth " + money(acc.nonCash)
							+ " are excluded (no cash received).");
				}
				if (acc.notMonth.signum() != 0) {
					notes.add(currency + ": " + money(acc.notMonth)
							+ " of course-payment amounts from the loaded orders cannot be placed in a billing month "
							+ "(module or undated session purchases) and is excluded from the month view; related fees "
							+ "and postage are not included in this reconciliation amount.");
				}
			}
			notes.add(0, "Amounts are course payments net of discounts. Service fees and postage are collected on "
					+ "top of these amounts and shown separately. Refunds and chargebacks are negative rows; all "
					+ "totals are net.");
			if (period.mode() == Mode.DATES) {
				notes.add(1, "Manual receipts are dated by the day the money was received (no time). A receipt "
						+ "recorded later with an earlier received date can change a past period - this report is "
						+ "as of its generation time.");
			}
			else {
				notes.add(1, "Months mode lists payments for lines billed in any selected month (monthly fees and "
						+ "sessions of that month), whatever the payment date. Module purchases, undated sessions "
						+ "and legacy course payments have no billing month and are not shown.");
			}
			if (!in.criteria().allCourses()) {
				notes.add("Course filter: an order-level facility fee is reported under the order's first course "
						+ "and is excluded when that course is not selected.");
			}

			List<String> months = period.months() == null ? null
					: period.months().stream().map(YearMonth::toString).toList();
			Filters filters = new Filters(List.copyOf(in.selectedCourses()),
					in.criteria().paymentMethod() == null ? null : in.criteria().paymentMethod().name(),
					period.mode().name(), months, period.startDate(), period.endDate(),
					ReportPeriodRules.compactPeriodLabel(period.mode(), period.months(), period.startDate(),
							period.endDate()));
			return new StudentPaymentReport(null, null, in.generatedAt(), in.zone().getId(),
					new Institute(in.instituteName()), filters, List.copyOf(sections),
					new Reconciliation(List.copyOf(byCurrency), List.copyOf(notes)), rowCount);
		}

		private String courseName(UUID courseId) {
			String name = courseId == null ? null : in.courseNames().get(courseId);
			return name == null ? "Unknown course" : name;
		}

		private static String studentName(StudentIndexEntry student) {
			return student == null || student.name() == null || student.name().isBlank() ? "Unknown student"
					: student.name().trim();
		}

	}

	/** Display method of an entry (3-R.5); refunds carry the original payment id, so they inherit it. */
	static MethodKey displayMethod(ReportLedgerEntry entry, ManualReceiptFact receipt) {
		if (entry.method() == null) {
			return new MethodKey("UNKNOWN", false);
		}
		return switch (entry.method()) {
			case GATEWAY -> new MethodKey("ONLINE", false);
			case MANUAL_SLIP -> new MethodKey("BANK_TRANSFER", false);
			case MANUAL_RECEIPT -> new MethodKey(receipt == null || receipt.paymentMethod() == null ? "UNKNOWN"
					: receipt.paymentMethod(), true);
			default -> new MethodKey("UNKNOWN", false);
		};
	}

	static int methodRank(MethodKey key) {
		return switch (key.method() + ":" + key.manual()) {
			case "ONLINE:false" -> 0;
			case "BANK_TRANSFER:false" -> 1;
			case "CASH:true" -> 2;
			case "BANK_TRANSFER:true" -> 3;
			case "CARD:true" -> 4;
			default -> 5;
		};
	}

	static String money(BigDecimal amount) {
		return ReportPeriodRules.money(amount);
	}

}
