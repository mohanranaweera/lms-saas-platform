package com.lms.reportinganalytics.service;

import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry.ReportCourseAllocation;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry.ReportLineAllocation;
import com.lms.paymentmanagement.api.ManualReceiptFact;
import com.lms.paymentmanagement.api.OrderCourseCharge;
import com.lms.paymentmanagement.api.OrderLineSnapshot;
import com.lms.reportinganalytics.service.StudentPaymentReport.CourseRef;
import com.lms.reportinganalytics.service.support.ReportPeriodRules;
import com.lms.reportinganalytics.service.support.ReportPeriodRules.Mode;
import com.lms.reportinganalytics.service.support.ReportPeriodRules.Period;
import com.lms.usermanagement.api.StudentIndexEntry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * RPT-02 synthetic calculator inputs (test-only). An {@link Book} collects
 * students, courses and orders; an order's entries carry course / line
 * allocations computed exactly as {@code RevenueAllocator} + {@code
 * RevenueLineSplitter} do (A2 shares, 2 dp HALF_UP, remainder to the largest
 * share), so every dataset the tests and sample PDFs use is PRODUCED by
 * {@link StudentPaymentCalculator} from realistic ledger inputs.
 */
public final class StudentPaymentFixtures {

	public static final ZoneId ZONE = ZoneId.of("Asia/Colombo");

	public static final Instant GENERATED = Instant.parse("2026-10-22T10:00:00Z");

	private static final AtomicCounter IDS = new AtomicCounter();

	private StudentPaymentFixtures() {
	}

	public static BigDecimal d(String value) {
		return new BigDecimal(value);
	}

	public static UUID id(String prefix) {
		return UUID.fromString(String.format("%s-0000-0000-0000-%012d", prefix, IDS.next()));
	}

	public static StudentPaymentCriteria dates(String start, String end, List<UUID> courses, PaymentMethod method) {
		return new StudentPaymentCriteria(courses, method,
				new Period(Mode.DATES, null, LocalDate.parse(start), LocalDate.parse(end)));
	}

	public static StudentPaymentCriteria months(List<UUID> courses, PaymentMethod method, String... months) {
		TreeSet<YearMonth> set = new TreeSet<>();
		for (String month : months) {
			set.add(YearMonth.parse(month));
		}
		return new StudentPaymentCriteria(courses, method,
				new Period(Mode.MONTHS, Collections.unmodifiableSortedSet(set), null, null));
	}

	public static final class Book {

		final List<ReportLedgerEntry> entries = new ArrayList<>();

		final List<OrderLineSnapshot> lines = new ArrayList<>();

		final List<OrderCourseCharge> charges = new ArrayList<>();

		final Map<UUID, UUID> studentByOrder = new HashMap<>();

		final List<ManualReceiptFact> receipts = new ArrayList<>();

		final Map<UUID, String> courseNames = new LinkedHashMap<>();

		final List<StudentIndexEntry> students = new ArrayList<>();

		final Map<UUID, YearMonth> lessonMonths = new HashMap<>();

		String institute = "Sample Institute of Learning";

		public Book institute(String name) {
			this.institute = name;
			return this;
		}

		public UUID student(String name, String indexNumber) {
			UUID id = id("50000000");
			students.add(new StudentIndexEntry(id, name, indexNumber));
			return id;
		}

		public UUID course(String name) {
			UUID id = id("60000000");
			courseNames.put(id, name);
			return id;
		}

		public UUID lesson(YearMonth billingMonth) {
			UUID id = id("70000000");
			if (billingMonth != null) {
				lessonMonths.put(id, billingMonth);
			}
			return id;
		}

		public Order order(UUID studentId) {
			return new Order(this, studentId);
		}

		public List<ReportLedgerEntry> entries() {
			return entries;
		}

		public StudentPaymentCalculator.Input input(StudentPaymentCriteria criteria) {
			List<CourseRef> selected = criteria.courseIds()
				.stream()
				.map(c -> new CourseRef(c, courseNames.get(c)))
				.toList();
			return new StudentPaymentCalculator.Input(criteria, ZONE, GENERATED, institute, selected,
					List.copyOf(entries), List.copyOf(lines), List.copyOf(charges), Map.copyOf(studentByOrder),
					List.copyOf(receipts), Map.copyOf(lessonMonths), Map.copyOf(courseNames), List.copyOf(students),
					"LKR");
		}

		public StudentPaymentCalculator.Result calculate(StudentPaymentCriteria criteria) {
			return StudentPaymentCalculator.calculate(input(criteria));
		}

	}

	private record LineSpec(UUID lineId, int lineNo, String scope, UUID courseId, UUID lessonId, YearMonth month,
			String label, BigDecimal unit, BigDecimal fee, BigDecimal discount) {

		BigDecimal total() {
			return unit.add(fee).subtract(discount);
		}

		BigDecimal share() {
			return unit.subtract(discount);
		}

	}

	public static final class Order {

		private final Book book;

		private final UUID orderId = StudentPaymentFixtures.id("80000000");

		private final UUID studentId;

		private final List<LineSpec> specs = new ArrayList<>();

		private final Map<UUID, BigDecimal> postage = new LinkedHashMap<>();

		private boolean legacy;

		private UUID legacyCourse;

		private BigDecimal legacyAmount;

		private boolean closed;

		Order(Book book, UUID studentId) {
			this.book = book;
			this.studentId = studentId;
			book.studentByOrder.put(orderId, studentId);
		}

		public UUID id() {
			return orderId;
		}

		public Order monthly(UUID course, String month, String unit, String fee) {
			return monthly(course, month, unit, fee, "0.00");
		}

		public Order monthly(UUID course, String month, String unit, String fee, String discount) {
			YearMonth ym = YearMonth.parse(month);
			specs.add(new LineSpec(StudentPaymentFixtures.id("90000000"), specs.size() + 1, "MONTHLY", course, null, ym, "Monthly fee " + month,
					d(unit), d(fee), d(discount)));
			return this;
		}

		public Order module(UUID course, String label, String unit, String fee) {
			specs.add(new LineSpec(StudentPaymentFixtures.id("90000000"), specs.size() + 1, "MODULE", course, null, null, label, d(unit),
					d(fee), d("0.00")));
			return this;
		}

		public Order lesson(UUID course, UUID lessonId, String label, String unit, String fee) {
			specs.add(new LineSpec(StudentPaymentFixtures.id("90000000"), specs.size() + 1, "LESSON", course, lessonId, null, label, d(unit),
					d(fee), d("0.00")));
			return this;
		}

		public Order postage(UUID course, String amount) {
			postage.put(course, d(amount));
			return this;
		}

		public Order legacy(UUID course, String amount) {
			legacy = true;
			legacyCourse = course;
			legacyAmount = d(amount);
			return this;
		}

		public BigDecimal amount() {
			if (legacy) {
				return legacyAmount;
			}
			BigDecimal sum = BigDecimal.ZERO;
			for (LineSpec spec : specs) {
				sum = sum.add(spec.total());
			}
			for (BigDecimal p : postage.values()) {
				sum = sum.add(p);
			}
			return sum.setScale(2);
		}

		private void close() {
			if (closed) {
				return;
			}
			closed = true;
			if (legacy) {
				return;
			}
			Map<UUID, BigDecimal> feeByCourse = new LinkedHashMap<>();
			for (LineSpec spec : specs) {
				book.lines.add(new OrderLineSnapshot(spec.lineId(), orderId, studentId, spec.lineNo(), spec.scope(),
						spec.courseId(), null, spec.lessonId(), spec.month() == null ? null : spec.month().getYear(),
						spec.month() == null ? null : spec.month().getMonthValue(), spec.scope() + ":" + spec.lineId(),
						spec.label(), spec.unit(), spec.fee(), spec.total(), "LKR"));
				feeByCourse.merge(spec.courseId(), spec.fee(), BigDecimal::add);
			}
			for (Map.Entry<UUID, BigDecimal> fee : feeByCourse.entrySet()) {
				book.charges.add(new OrderCourseCharge(orderId, fee.getKey(), fee.getValue(),
						postage.getOrDefault(fee.getKey(), d("0.00"))));
			}
		}

		/** A confirmation of the full order amount. */
		public ReportLedgerEntry confirm(PaymentMethod method, String at) {
			return entry(false, amount(), Instant.parse(at), method, UUID.randomUUID(), null);
		}

		/** An ADR-030 manual receipt (received = order amount - adjustment). */
		public ReportLedgerEntry receipt(String receiptMethod, String receivedOn, String recordedAt, String adjustment) {
			UUID paymentId = UUID.randomUUID();
			BigDecimal received = amount().subtract(d(adjustment));
			book.receipts.add(new ManualReceiptFact(UUID.randomUUID(), paymentId, orderId, receiptMethod,
					LocalDate.parse(receivedOn), "LKR", amount(), d(adjustment), received, Instant.parse(recordedAt)));
			return entry(false, received, Instant.parse(recordedAt), PaymentMethod.MANUAL_RECEIPT, paymentId, null);
		}

		/** A refund / chargeback of {@code original} (negative amount). */
		public ReportLedgerEntry refund(ReportLedgerEntry original, String amount, String at) {
			return entry(true, d(amount), Instant.parse(at), original.method(), original.paymentId(),
					original.entryId());
		}

		private ReportLedgerEntry entry(boolean refund, BigDecimal amount, Instant at, PaymentMethod method,
				UUID paymentId, UUID reverses) {
			close();
			List<ReportCourseAllocation> courses = legacy
					? List.of(new ReportCourseAllocation(legacyCourse, amount, List.of(), null))
					: allocate(amount);
			ReportLedgerEntry entry = new ReportLedgerEntry(UUID.randomUUID(), orderId, paymentId, refund, amount, at,
					reverses, "LKR", method, !legacy, courses);
			book.entries.add(entry);
			return entry;
		}

		/** RevenueAllocator + RevenueLineSplitter (ADR-029 A2), reproduced for synthetic inputs. */
		private List<ReportCourseAllocation> allocate(BigDecimal amount) {
			BigDecimal orderAmount = amount();
			Map<UUID, BigDecimal> shares = new LinkedHashMap<>();
			for (LineSpec spec : specs) {
				shares.merge(spec.courseId(), spec.share(), BigDecimal::add);
			}
			if (amount.signum() == 0 || orderAmount.signum() <= 0) {
				return List.of();
			}
			List<Map.Entry<UUID, BigDecimal>> ordered = new ArrayList<>(shares.entrySet());
			ordered.sort(Comparator.<Map.Entry<UUID, BigDecimal>, BigDecimal>comparing(Map.Entry::getValue)
				.reversed()
				.thenComparing(Map.Entry::getKey));
			BigDecimal magnitude = amount.abs();
			BigDecimal shareTotal = BigDecimal.ZERO;
			for (Map.Entry<UUID, BigDecimal> e : ordered) {
				shareTotal = shareTotal.add(e.getValue());
			}
			BigDecimal attributable = magnitude.multiply(shareTotal).divide(orderAmount, 2, RoundingMode.HALF_UP);
			List<BigDecimal> parts = new ArrayList<>();
			BigDecimal allocated = BigDecimal.ZERO;
			for (Map.Entry<UUID, BigDecimal> e : ordered) {
				BigDecimal part = magnitude.multiply(e.getValue()).divide(orderAmount, 2, RoundingMode.HALF_UP);
				parts.add(part);
				allocated = allocated.add(part);
			}
			parts.set(0, parts.get(0).add(attributable.subtract(allocated)));
			List<ReportCourseAllocation> result = new ArrayList<>();
			for (int i = 0; i < ordered.size(); i++) {
				UUID course = ordered.get(i).getKey();
				BigDecimal part = parts.get(i);
				List<ReportLineAllocation> lineParts = splitLines(course, part);
				result.add(new ReportCourseAllocation(course, amount.signum() < 0 ? part.negate() : part,
						amount.signum() < 0 ? negate(lineParts) : lineParts, null));
			}
			return result;
		}

		private List<ReportLineAllocation> splitLines(UUID course, BigDecimal magnitude) {
			List<LineSpec> courseLines = specs.stream().filter(s -> s.courseId().equals(course)).toList();
			BigDecimal total = BigDecimal.ZERO;
			for (LineSpec spec : courseLines) {
				total = total.add(spec.share());
			}
			if (magnitude.signum() == 0 || total.signum() <= 0) {
				return List.of();
			}
			List<BigDecimal> parts = new ArrayList<>();
			BigDecimal allocated = BigDecimal.ZERO;
			int target = -1;
			for (int i = 0; i < courseLines.size(); i++) {
				BigDecimal part = magnitude.multiply(courseLines.get(i).share()).divide(total, 2, RoundingMode.HALF_UP);
				parts.add(part);
				allocated = allocated.add(part);
				if (target < 0 || courseLines.get(i).share().compareTo(courseLines.get(target).share()) > 0) {
					target = i;
				}
			}
			parts.set(target, parts.get(target).add(magnitude.subtract(allocated)));
			List<ReportLineAllocation> result = new ArrayList<>();
			for (int i = 0; i < courseLines.size(); i++) {
				if (parts.get(i).signum() != 0) {
					LineSpec spec = courseLines.get(i);
					YearMonth month = spec.month() != null ? spec.month()
							: spec.lessonId() == null ? null : book.lessonMonths.get(spec.lessonId());
					result.add(new ReportLineAllocation(spec.lineId(), spec.lineNo(), spec.scope(),
							month == null ? null : month.getYear(), month == null ? null : month.getMonthValue(),
							parts.get(i)));
				}
			}
			return result;
		}

		private static List<ReportLineAllocation> negate(List<ReportLineAllocation> lines) {
			return lines.stream()
				.map(l -> new ReportLineAllocation(l.lineId(), l.lineNo(), l.scope(), l.billingYear(),
						l.billingMonth(), l.amount().negate()))
				.toList();
		}

	}

	private static final class AtomicCounter {

		private long value;

		synchronized long next() {
			return ++value;
		}

	}

	/** Sum of a row field over rows. */
	public static BigDecimal sum(List<StudentPaymentRow> rows, java.util.function.Function<StudentPaymentRow, String> f) {
		BigDecimal total = BigDecimal.ZERO;
		for (StudentPaymentRow row : rows) {
			total = total.add(new BigDecimal(f.apply(row)));
		}
		return total;
	}

	static String money(BigDecimal value) {
		return ReportPeriodRules.money(value);
	}

}
