package com.lms.reportinganalytics.service;

import com.lms.common.api.FieldError;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * RPT-02 grouping (spec section 5) - presentation only: it reorders the stored
 * rows and computes group subtotals over the WHOLE run; totals and counts never
 * change. Keys are ids ({@code studentId}, {@code courseId}), never names, so
 * two students with the same name stay separate. Groups sort by display name
 * (case-insensitive), then name, then id; rows inside the finest group keep the
 * base order (payment date, entry id, line no). Every group is per currency.
 * Used identically by the preview pages and the PDF. Pure.
 */
public final class StudentPaymentGrouping {

	public enum GroupBy {

		NONE, STUDENT, COURSE, STUDENT_COURSE;

		/** {@code null}/blank = NONE; anything else unknown is a {@code 422 REPORT_VALIDATION}. */
		public static GroupBy parse(String value) {
			if (value == null || value.isBlank()) {
				return NONE;
			}
			try {
				return valueOf(value.trim());
			}
			catch (IllegalArgumentException ex) {
				throw new ReportValidationException(
						List.of(new FieldError("groupBy", "must be one of NONE, STUDENT, COURSE, STUDENT_COURSE")));
			}
		}

	}

	public static final String STUDENT = "STUDENT";

	public static final String COURSE = "COURSE";

	/**
	 * Subtotals of one group over the whole run. Identity = (currency, level,
	 * parentKey, key); {@code parentKey} is the student id of a course group
	 * nested in a student (STUDENT_COURSE), else {@code null}. {@code
	 * indexNumber} is set on student groups only. {@code receiptCount} = distinct
	 * confirmed payments with a row in the group.
	 */
	public record Group(String level, String key, String parentKey, String label, String indexNumber,
			String currency, String coursePayment, String serviceFee, String postage, long receiptCount,
			long rowCount) {

	}

	/** One preview row: the stored row plus its position in the grouped order. */
	public record RowView(int rowNo, String currency, UUID entryId, UUID receiptId, boolean refund, UUID studentId,
			String studentName, String indexNumber, UUID courseId, String courseName, String reason, String scope,
			String method, boolean manual, String paymentDate, boolean dateOnly, Instant recordedAt, String amount,
			String serviceFee, String postage) {

	}

	/** {@code GET /runs/{runId}/rows} page; {@code groups} = every group with a row on this page. */
	public record RowsPage(String groupBy, int page, int size, int totalRows, int totalPages, List<RowView> rows,
			List<Group> groups) {

	}

	/** The run's rows in grouped order with every group's subtotals. */
	public record Grouped(GroupBy groupBy, List<StudentPaymentRow> rows, Map<String, Group> groups) {

		/** The groups a row belongs to, outermost first (empty for NONE). */
		public List<Group> groupsOf(StudentPaymentRow row) {
			List<Group> result = new ArrayList<>(2);
			for (String id : groupIds(groupBy, row)) {
				result.add(groups.get(id));
			}
			return result;
		}

	}

	private StudentPaymentGrouping() {
	}

	public static Grouped group(List<StudentPaymentRow> baseRows, GroupBy groupBy) {
		List<Indexed> indexed = new ArrayList<>(baseRows.size());
		for (int i = 0; i < baseRows.size(); i++) {
			indexed.add(new Indexed(i, baseRows.get(i)));
		}
		indexed.sort(comparator(groupBy));
		List<StudentPaymentRow> rows = indexed.stream().map(Indexed::row).toList();

		Map<String, Acc> accs = new LinkedHashMap<>();
		for (StudentPaymentRow row : rows) {
			List<String> ids = groupIds(groupBy, row);
			for (int level = 0; level < ids.size(); level++) {
				String id = ids.get(level);
				int depth = level;
				Acc acc = accs.computeIfAbsent(id, key -> new Acc(groupBy, depth, row));
				acc.add(row);
			}
		}
		Map<String, Group> groups = new LinkedHashMap<>();
		accs.forEach((id, acc) -> groups.put(id, acc.toGroup()));
		return new Grouped(groupBy, rows, groups);
	}

	/** One page of the grouped rows (0-based {@code page}) with the groups present on it. */
	public static RowsPage page(Grouped grouped, int page, int size) {
		int total = grouped.rows().size();
		int totalPages = total == 0 ? 0 : (total + size - 1) / size;
		int from = (int) Math.min((long) page * size, total);
		int to = Math.min(from + size, total);
		List<RowView> views = new ArrayList<>(to - from);
		Map<String, Group> onPage = new LinkedHashMap<>();
		for (int i = from; i < to; i++) {
			StudentPaymentRow row = grouped.rows().get(i);
			views.add(view(i + 1, row));
			for (String id : groupIds(grouped.groupBy(), row)) {
				onPage.putIfAbsent(id, grouped.groups().get(id));
			}
		}
		return new RowsPage(grouped.groupBy().name(), page, size, total, totalPages, List.copyOf(views),
				List.copyOf(onPage.values()));
	}

	static RowView view(int rowNo, StudentPaymentRow row) {
		return new RowView(rowNo, row.currency(), row.entryId(), row.receiptId(), row.refund(), row.studentId(),
				row.studentName(), row.indexNumber(), row.courseId(), row.courseName(), row.reason(), row.scope(),
				row.method(), row.manual(), row.paymentDate(), row.dateOnly(), row.recordedAt(), row.amount(),
				row.serviceFee(), row.postage());
	}

	// ------------------------------------------------------------------

	private record Indexed(int index, StudentPaymentRow row) {

	}

	private static String studentKey(StudentPaymentRow row) {
		return row.studentId() == null ? "unknown" : row.studentId().toString();
	}

	private static String courseKey(StudentPaymentRow row) {
		return row.courseId() == null ? "unknown" : row.courseId().toString();
	}

	static List<String> groupIds(GroupBy groupBy, StudentPaymentRow row) {
		String currency = row.currency();
		return switch (groupBy) {
			case NONE -> List.of();
			case STUDENT -> List.of(currency + "|" + STUDENT + "||" + studentKey(row));
			case COURSE -> List.of(currency + "|" + COURSE + "||" + courseKey(row));
			case STUDENT_COURSE -> List.of(currency + "|" + STUDENT + "||" + studentKey(row),
					currency + "|" + COURSE + "|" + studentKey(row) + "|" + courseKey(row));
		};
	}

	private static Comparator<Indexed> comparator(GroupBy groupBy) {
		Comparator<Indexed> byCurrency = Comparator.comparing(i -> i.row().currency());
		Comparator<Indexed> byStudent = Comparator
			.<Indexed, String>comparing(i -> i.row().studentName(), String.CASE_INSENSITIVE_ORDER)
			.thenComparing(i -> i.row().studentName())
			.thenComparing(i -> studentKey(i.row()));
		Comparator<Indexed> byCourse = Comparator
			.<Indexed, String>comparing(i -> i.row().courseName(), String.CASE_INSENSITIVE_ORDER)
			.thenComparing(i -> i.row().courseName())
			.thenComparing(i -> courseKey(i.row()));
		Comparator<Indexed> base = Comparator.comparingInt(Indexed::index);
		return switch (groupBy) {
			case NONE -> base;
			case STUDENT -> byCurrency.thenComparing(byStudent).thenComparing(base);
			case COURSE -> byCurrency.thenComparing(byCourse).thenComparing(base);
			case STUDENT_COURSE -> byCurrency.thenComparing(byStudent).thenComparing(byCourse).thenComparing(base);
		};
	}

	private static final class Acc {

		private final String level;

		private final String key;

		private final String parentKey;

		private final String label;

		private final String indexNumber;

		private final String currency;

		private BigDecimal course = BigDecimal.ZERO;

		private BigDecimal fee = BigDecimal.ZERO;

		private BigDecimal postage = BigDecimal.ZERO;

		private final Set<UUID> receipts = new HashSet<>();

		private long rows;

		Acc(GroupBy groupBy, int depth, StudentPaymentRow first) {
			boolean studentLevel = depth == 0 && (groupBy == GroupBy.STUDENT || groupBy == GroupBy.STUDENT_COURSE);
			this.level = studentLevel ? STUDENT : COURSE;
			this.key = studentLevel ? studentKey(first) : courseKey(first);
			this.parentKey = groupBy == GroupBy.STUDENT_COURSE && depth == 1 ? studentKey(first) : null;
			this.label = studentLevel ? first.studentName() : first.courseName();
			this.indexNumber = studentLevel ? first.indexNumber() : null;
			this.currency = first.currency();
		}

		void add(StudentPaymentRow row) {
			course = course.add(new BigDecimal(row.amount()));
			fee = fee.add(new BigDecimal(row.serviceFee()));
			postage = postage.add(new BigDecimal(row.postage()));
			rows++;
			if (!row.refund()) {
				receipts.add(row.entryId());
			}
		}

		Group toGroup() {
			return new Group(level, key, parentKey, label, indexNumber, currency, StudentPaymentCalculator.money(course),
					StudentPaymentCalculator.money(fee), StudentPaymentCalculator.money(postage), receipts.size(),
					rows);
		}

	}

}
