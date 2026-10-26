package com.lms.reportinganalytics.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry;
import com.lms.paymentmanagement.api.OrderLineSnapshot;
import com.lms.usermanagement.api.UnpaidStudentProfile;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StudentBehaviorCalculatorTest {
    private static final UUID COURSE = UUID.randomUUID();
    private static final YearMonth OCT = YearMonth.of(2026, 10);

    @Test
    void classifiesAdjacentMonthSetsByStudentIdAndDeduplicatesLedgerRetries() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        List<OrderLineSnapshot> lines = List.of(line(a, OCT.minusMonths(1)), line(b, OCT.minusMonths(1)), line(c, OCT.minusMonths(1)), line(b, OCT), line(c, OCT), line(d, OCT));
        List<ReportLedgerEntry> entries = List.of(entry(lines.get(0)), entry(lines.get(1)), entry(lines.get(2)), entry(lines.get(3)), entry(lines.get(4)), entry(lines.get(5)), entry(lines.get(5)));
        StudentBehaviorCalculator.Result result = calculate(lines, entries, List.of(profile(a, "0001", "Same"), profile(b, "0002", "Same"), profile(c, "0003", "C"), profile(d, "0004", "D")));
        assertThat(result.report().counts()).isEqualTo(new StudentBehaviorReport.Counts(1, 1, 2));
        assertThat(result.newStudents()).extracting(StudentBehaviorRow::studentId).containsExactly(d);
        assertThat(result.droppedStudents()).extracting(StudentBehaviorRow::studentId).containsExactly(a);
        assertThat(result.continuingStudents()).extracting(StudentBehaviorRow::studentId).containsExactly(b, c);
    }

    @Test
    void excludesPartialNonCashAndReversedCoverageAndHandlesJanuaryComparison() {
        UUID paid = UUID.randomUUID(), partial = UUID.randomUUID(), free = UUID.randomUUID(), reversed = UUID.randomUUID();
        YearMonth january = YearMonth.of(2027, 1);
        List<OrderLineSnapshot> lines = List.of(line(paid, january.minusMonths(1)), line(paid, january), line(partial, january), line(free, january), line(reversed, january));
        List<ReportLedgerEntry> entries = List.of(entry(lines.get(0)), entry(lines.get(1)), partial(lines.get(2)), method(lines.get(3), PaymentMethod.FREE, amount("100")), entry(lines.get(4)), reversal(lines.get(4)));
        StudentBehaviorCalculator.Result result = calculate(january, lines, entries, List.of(profile(paid, "0001", "Paid"), profile(partial, "0002", "Partial"), profile(free, "0003", "Free"), profile(reversed, "0004", "Reversed")));
        assertThat(result.report().classification().comparisonMonth()).isEqualTo(YearMonth.of(2026, 12));
        assertThat(result.report().counts()).isEqualTo(new StudentBehaviorReport.Counts(0, 0, 1));
        assertThat(result.continuingStudents()).extracting(StudentBehaviorRow::studentId).containsExactly(paid);
    }

    private static StudentBehaviorCalculator.Result calculate(List<OrderLineSnapshot> lines, List<ReportLedgerEntry> entries, List<UnpaidStudentProfile> profiles) { return calculate(OCT, lines, entries, profiles); }
    private static StudentBehaviorCalculator.Result calculate(YearMonth month, List<OrderLineSnapshot> lines, List<ReportLedgerEntry> entries, List<UnpaidStudentProfile> profiles) {
        return StudentBehaviorCalculator.calculate(new StudentBehaviorCalculator.Input(new StudentBehaviorCriteria(COURSE, month), ZoneId.of("UTC"), Instant.parse("2026-10-15T12:00:00Z"), "Institute", new StudentBehaviorReport.CourseRef(COURSE, "Course"), lines, entries, profiles));
    }
    private static OrderLineSnapshot line(UUID student, YearMonth month) { UUID order = UUID.randomUUID(), id = UUID.randomUUID(); return new OrderLineSnapshot(id, order, student, 1, "MONTHLY", COURSE, null, null, month.getYear(), month.getMonthValue(), "MONTHLY:" + COURSE + ":" + month, month.toString(), amount("100"), BigDecimal.ZERO, amount("100"), "LKR"); }
    private static ReportLedgerEntry entry(OrderLineSnapshot line) { return method(line, PaymentMethod.GATEWAY, amount("100")); }
    private static ReportLedgerEntry partial(OrderLineSnapshot line) { return method(line, PaymentMethod.GATEWAY, amount("50")); }
    private static ReportLedgerEntry reversal(OrderLineSnapshot line) { return method(line, PaymentMethod.GATEWAY, amount("-100")); }
    private static ReportLedgerEntry method(OrderLineSnapshot line, PaymentMethod method, BigDecimal amount) { return new ReportLedgerEntry(UUID.randomUUID(), line.orderId(), UUID.randomUUID(), amount.signum() < 0, amount, Instant.parse("2026-10-15T12:00:00Z"), null, "LKR", method, true, List.of(new ReportLedgerEntry.ReportCourseAllocation(COURSE, amount, List.of(new ReportLedgerEntry.ReportLineAllocation(line.lineId(), 1, "MONTHLY", line.periodYear(), line.periodMonth(), amount)), null))); }
    private static UnpaidStudentProfile profile(UUID id, String index, String name) { return new UnpaidStudentProfile(UUID.randomUUID(), id, index, name, name + "@example.test", "0770000000"); }
    private static BigDecimal amount(String value) { return new BigDecimal(value); }
}
