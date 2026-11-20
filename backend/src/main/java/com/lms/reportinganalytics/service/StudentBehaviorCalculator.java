package com.lms.reportinganalytics.service;

import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry;
import com.lms.paymentmanagement.api.OrderLineSnapshot;
import com.lms.usermanagement.api.UnpaidStudentProfile;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pure adjacent-month confirmed cash-coverage classifier. */
public final class StudentBehaviorCalculator {
    private static final Set<PaymentMethod> CASH = Set.of(PaymentMethod.GATEWAY, PaymentMethod.MANUAL_SLIP, PaymentMethod.MANUAL_RECEIPT);
    private static final Comparator<StudentBehaviorRow> ORDER = Comparator
            .comparing(StudentBehaviorRow::indexNumber, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(StudentBehaviorRow::studentName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(StudentBehaviorRow::studentId);

    public record Input(StudentBehaviorCriteria criteria, ZoneId zone, Instant generatedAt, String instituteName,
            StudentBehaviorReport.CourseRef course, List<OrderLineSnapshot> orderLines,
            List<ReportLedgerEntry> ledgerEntries, List<UnpaidStudentProfile> profiles) { }
    public record Result(StudentBehaviorReport report, List<StudentBehaviorRow> newStudents,
            List<StudentBehaviorRow> droppedStudents, List<StudentBehaviorRow> continuingStudents) { }

    private StudentBehaviorCalculator() { }

    public static Result calculate(Input in) {
        YearMonth currentMonth = in.criteria().billingMonth();
        YearMonth previousMonth = currentMonth.minusMonths(1);
        Set<UUID> current = coveredStudents(in, currentMonth);
        Set<UUID> previous = coveredStudents(in, previousMonth);
        Set<UUID> newIds = difference(current, previous);
        Set<UUID> droppedIds = difference(previous, current);
        Set<UUID> continuingIds = new LinkedHashSet<>(current);
        continuingIds.retainAll(previous);
        Map<UUID, UnpaidStudentProfile> profiles = new HashMap<>();
        in.profiles().forEach(profile -> profiles.put(profile.userId(), profile));
        List<StudentBehaviorRow> added = rows(newIds, profiles);
        List<StudentBehaviorRow> dropped = rows(droppedIds, profiles);
        List<StudentBehaviorRow> continuing = rows(continuingIds, profiles);
        String label = currentMonth.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + currentMonth.getYear();
        YearMonth now = YearMonth.from(in.generatedAt().atZone(in.zone()));
        StudentBehaviorReport report = new StudentBehaviorReport(null, null, in.generatedAt(), in.zone().getId(),
                new StudentBehaviorReport.Institute(in.instituteName()), in.course(),
                new StudentBehaviorReport.Filters(currentMonth, label),
                new StudentBehaviorReport.Classification("CONFIRMED_CASH_MONTHLY_COURSE_COVERAGE", previousMonth,
                        "AVAILABLE", currentMonth.equals(now), in.generatedAt()),
                new StudentBehaviorReport.Counts(added.size(), dropped.size(), continuing.size()));
        return new Result(report, added, dropped, continuing);
    }

    private static Set<UUID> coveredStudents(Input in, YearMonth month) {
        Map<UUID, OrderLineSnapshot> requiredLines = new LinkedHashMap<>();
        Map<UUID, BigDecimal> requiredByOrder = new HashMap<>();
        Map<UUID, UUID> studentByOrder = new HashMap<>();
        for (OrderLineSnapshot line : in.orderLines()) {
            if (line == null || line.studentId() == null || !in.criteria().courseId().equals(line.courseId())
                    || !"MONTHLY".equals(line.scope()) || line.periodYear() == null || line.periodMonth() == null
                    || !month.equals(YearMonth.of(line.periodYear(), line.periodMonth()))) continue;
            requiredLines.put(line.lineId(), line);
            studentByOrder.put(line.orderId(), line.studentId());
            requiredByOrder.merge(line.orderId(), line.courseShare(), BigDecimal::add);
        }
        Map<UUID, BigDecimal> coveredByOrder = new HashMap<>();
        Set<UUID> seenEntries = new HashSet<>();
        for (ReportLedgerEntry entry : in.ledgerEntries()) {
            if (entry == null || entry.entryId() == null || !seenEntries.add(entry.entryId()) || !CASH.contains(entry.method())
                    || !studentByOrder.containsKey(entry.orderId())) continue;
            for (ReportLedgerEntry.ReportCourseAllocation course : entry.courses()) {
                if (!in.criteria().courseId().equals(course.courseId())) continue;
                for (ReportLedgerEntry.ReportLineAllocation line : course.lines()) {
                    if (requiredLines.containsKey(line.lineId()) && line.hasBillingMonth()
                            && line.billingYear() == month.getYear() && line.billingMonth() == month.getMonthValue()) {
                        coveredByOrder.merge(entry.orderId(), line.amount(), BigDecimal::add);
                    }
                }
            }
        }
        Set<UUID> result = new LinkedHashSet<>();
        for (Map.Entry<UUID, BigDecimal> required : requiredByOrder.entrySet()) {
            BigDecimal amount = required.getValue();
            BigDecimal covered = coveredByOrder.getOrDefault(required.getKey(), BigDecimal.ZERO);
            if (amount.signum() <= 0 || covered.compareTo(amount) >= 0) result.add(studentByOrder.get(required.getKey()));
        }
        return result;
    }

    private static Set<UUID> difference(Set<UUID> left, Set<UUID> right) {
        Set<UUID> result = new LinkedHashSet<>(left); result.removeAll(right); return result;
    }

    private static List<StudentBehaviorRow> rows(Set<UUID> ids, Map<UUID, UnpaidStudentProfile> profiles) {
        List<StudentBehaviorRow> result = new ArrayList<>();
        for (UUID id : ids) {
            UnpaidStudentProfile p = profiles.get(id);
            result.add(new StudentBehaviorRow(p == null ? null : p.studentProfileId(), id, clean(p == null ? null : p.indexNumber()),
                    p == null || clean(p.name()).isEmpty() ? "Unknown student" : clean(p.name()),
                    clean(p == null ? null : p.email()), clean(p == null ? null : p.mobile())));
        }
        result.sort(ORDER); return List.copyOf(result);
    }
    private static String clean(String value) { return value == null || value.isBlank() ? "" : value.trim(); }
}
