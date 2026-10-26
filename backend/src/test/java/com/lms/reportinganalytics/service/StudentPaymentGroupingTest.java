package com.lms.reportinganalytics.service;

import static com.lms.reportinganalytics.service.StudentPaymentFixtures.dates;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.reportinganalytics.service.StudentPaymentFixtures.Book;
import com.lms.reportinganalytics.service.StudentPaymentGrouping.Group;
import com.lms.reportinganalytics.service.StudentPaymentGrouping.GroupBy;
import com.lms.reportinganalytics.service.StudentPaymentGrouping.Grouped;
import com.lms.reportinganalytics.service.StudentPaymentGrouping.RowView;
import com.lms.reportinganalytics.service.StudentPaymentGrouping.RowsPage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * RPT-02 grouping (spec section 5, acceptance I): presentation only - every
 * mode keeps the same rows and totals, group subtotals add up to the totals,
 * keys are ids (two students with the same name stay separate) and pages carry
 * the subtotals of the groups they show.
 */
class StudentPaymentGroupingTest {

	private static StudentPaymentCalculator.Result dataset() {
		Book book = new Book();
		UUID maths = book.course("Maths");
		UUID physics = book.course("Physics");
		UUID nimal1 = book.student("Nimal Perera", "0007");
		UUID nimal2 = book.student("Nimal Perera", "0012");
		UUID amali = book.student("amali Silva", "0100");
		book.order(nimal1).monthly(maths, "2026-10", "1000.00", "100.00").monthly(physics, "2026-10", "2000.00",
				"100.00").confirm(PaymentMethod.GATEWAY, "2026-10-08T03:00:00Z");
		book.order(nimal2).monthly(maths, "2026-10", "1500.00", "100.00").confirm(PaymentMethod.MANUAL_SLIP,
				"2026-10-08T04:00:00Z");
		book.order(amali).monthly(physics, "2026-10", "2500.00", "0.00").postage(physics, "150.00")
			.confirm(PaymentMethod.GATEWAY, "2026-10-08T05:00:00Z");
		book.order(nimal1).monthly(maths, "2026-11", "1000.00", "0.00").confirm(PaymentMethod.GATEWAY,
				"2026-10-08T06:00:00Z");
		return book.calculate(dates("2026-10-08", "2026-10-08", List.of(), null));
	}

	@Test
	void i_everyModeHasTheSameRowsAndTotalsAndSubtotalsAddUp() {
		StudentPaymentCalculator.Result result = dataset();
		List<StudentPaymentRow> base = result.rows();
		BigDecimal total = StudentPaymentFixtures.sum(base, StudentPaymentRow::amount);
		assertThat(total).isEqualByComparingTo(result.report().sections().get(0).summary().coursePaymentTotal());
		for (GroupBy mode : GroupBy.values()) {
			Grouped grouped = StudentPaymentGrouping.group(base, mode);
			assertThat(grouped.rows()).as(mode.name()).containsExactlyInAnyOrderElementsOf(base);
			if (mode == GroupBy.NONE) {
				assertThat(grouped.rows()).isEqualTo(base);
				assertThat(grouped.groups()).isEmpty();
				continue;
			}
			List<Group> top = grouped.groups().values().stream().filter(g -> g.parentKey() == null).toList();
			assertThat(top.stream().map(g -> new BigDecimal(g.coursePayment())).reduce(BigDecimal.ZERO,
					BigDecimal::add)).as(mode.name()).isEqualByComparingTo(total);
			assertThat(top.stream().map(g -> new BigDecimal(g.serviceFee())).reduce(BigDecimal.ZERO, BigDecimal::add))
				.isEqualByComparingTo("300.00");
			assertThat(top.stream().map(g -> new BigDecimal(g.postage())).reduce(BigDecimal.ZERO, BigDecimal::add))
				.isEqualByComparingTo("150.00");
			assertThat(top.stream().mapToLong(Group::rowCount).sum()).isEqualTo(base.size());
			if (mode == GroupBy.STUDENT_COURSE) {
				for (Group student : top) {
					List<Group> nested = grouped.groups().values().stream()
						.filter(g -> student.key().equals(g.parentKey()))
						.toList();
					assertThat(nested.stream().map(g -> new BigDecimal(g.coursePayment())).reduce(BigDecimal.ZERO,
							BigDecimal::add)).isEqualByComparingTo(student.coursePayment());
				}
			}
		}
	}

	@Test
	void i_keysAreIdsSoStudentsWithTheSameNameStaySeparateAndGroupsSortByNameThenId() {
		Grouped grouped = StudentPaymentGrouping.group(dataset().rows(), GroupBy.STUDENT);
		List<Group> students = new ArrayList<>(grouped.groups().values());
		assertThat(students).extracting(Group::label).containsExactly("amali Silva", "Nimal Perera", "Nimal Perera");
		assertThat(students.get(1).key()).isNotEqualTo(students.get(2).key());
		assertThat(students.get(1).key().compareTo(students.get(2).key())).isNegative();
		assertThat(students).extracting(Group::indexNumber).containsExactly("0100", "0007", "0012");
		Group nimal1 = students.get(1);
		assertThat(nimal1.receiptCount()).isEqualTo(2);
		assertThat(nimal1.rowCount()).isEqualTo(3);
		assertThat(nimal1.coursePayment()).isEqualTo("4000.00");
		// Rows of one student stay in payment-date order.
		List<StudentPaymentRow> rows = grouped.rows();
		assertThat(rows.get(1).recordedAt()).isBeforeOrEqualTo(rows.get(2).recordedAt());
	}

	@Test
	void i_pagesCarryTheSubtotalsOfTheGroupsTheyShowComputedOverTheWholeRun() {
		Grouped grouped = StudentPaymentGrouping.group(dataset().rows(), GroupBy.STUDENT_COURSE);
		RowsPage first = StudentPaymentGrouping.page(grouped, 0, 2);
		assertThat(first.totalRows()).isEqualTo(5);
		assertThat(first.totalPages()).isEqualTo(3);
		assertThat(first.rows()).extracting(RowView::rowNo).containsExactly(1, 2);
		Set<String> levels = new HashSet<>();
		first.groups().forEach(g -> levels.add(g.level()));
		assertThat(levels).containsExactlyInAnyOrder("STUDENT", "COURSE");
		RowsPage second = StudentPaymentGrouping.page(grouped, 1, 2);
		assertThat(second.rows()).extracting(RowView::rowNo).containsExactly(3, 4);
		// A student spanning both pages has the same whole-run subtotal on each.
		for (Group g : second.groups()) {
			assertThat(grouped.groups().containsValue(g)).isTrue();
		}
		RowsPage beyond = StudentPaymentGrouping.page(grouped, 9, 2);
		assertThat(beyond.rows()).isEmpty();
		assertThat(beyond.groups()).isEmpty();
	}

	@Test
	void unknownGroupByIsA422() {
		assertThat(GroupBy.parse(null)).isEqualTo(GroupBy.NONE);
		assertThat(GroupBy.parse("STUDENT_COURSE")).isEqualTo(GroupBy.STUDENT_COURSE);
		assertThatThrownBy(() -> GroupBy.parse("TEACHER")).isInstanceOf(ReportValidationException.class);
	}

}
