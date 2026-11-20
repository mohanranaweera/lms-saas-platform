package com.lms.reportinganalytics.service;

import com.lms.attendancemanagement.api.AttendanceReportFact;
import com.lms.enrollmentmanagement.api.UnpaidStudentsEnrollmentApi.ParticipationFact;
import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry.ReportCourseAllocation;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry.ReportLineAllocation;
import com.lms.paymentmanagement.api.OrderLineSnapshot;
import com.lms.usermanagement.api.UnpaidStudentProfile;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

/** Pure, deterministic enrollment-minus-payment report computation. */
public final class UnpaidStudentsCalculator {

	private static final Set<PaymentMethod> CASH_METHODS = Set.of(PaymentMethod.GATEWAY,
			PaymentMethod.MANUAL_SLIP, PaymentMethod.MANUAL_RECEIPT);

	private static final Comparator<UnpaidStudentRow> ROW_ORDER = Comparator
		.comparing(UnpaidStudentRow::indexNumber, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
		.thenComparing(UnpaidStudentRow::studentName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
		.thenComparing(UnpaidStudentRow::studentId);

	public record Input(UnpaidStudentsCriteria criteria, ZoneId zone, Instant generatedAt, String instituteName,
			UnpaidStudentsReport.CourseRef course, List<ParticipationFact> participants,
			List<OrderLineSnapshot> orderLines, List<ReportLedgerEntry> ledgerEntries,
			List<AttendanceReportFact> attendanceFacts, List<UnpaidStudentProfile> profiles) {
	}

	public record Result(UnpaidStudentsReport report, List<UnpaidStudentRow> rows) {
	}

	private UnpaidStudentsCalculator() {
	}

	public static Result calculate(Input in) {
		Set<UUID> participantIds = new LinkedHashSet<>();
		for (ParticipationFact fact : in.participants()) {
			if (fact != null && fact.studentId() != null) participantIds.add(fact.studentId());
		}
		Map<UUID, OrderLineSnapshot> requiredLineById = new HashMap<>();
		Map<UUID, BigDecimal> requiredByOrder = new HashMap<>();
		Map<UUID, UUID> studentByOrder = new HashMap<>();
		for (OrderLineSnapshot line : in.orderLines()) {
			if (line == null || !participantIds.contains(line.studentId()) || !in.criteria().courseId().equals(line.courseId())
					|| !"MONTHLY".equals(line.scope()) || line.periodYear() == null || line.periodMonth() == null
					|| !in.criteria().billingMonth().equals(YearMonth.of(line.periodYear(), line.periodMonth()))) continue;
			requiredLineById.put(line.lineId(), line);
			studentByOrder.put(line.orderId(), line.studentId());
			requiredByOrder.merge(line.orderId(), line.courseShare(), BigDecimal::add);
		}

		Map<UUID, BigDecimal> coveredByOrder = new HashMap<>();
		Set<UUID> seenEntries = new HashSet<>();
		for (ReportLedgerEntry entry : in.ledgerEntries()) {
			if (entry == null || entry.entryId() == null || !seenEntries.add(entry.entryId())
					|| entry.method() == null || !CASH_METHODS.contains(entry.method())) continue;
			if (!studentByOrder.containsKey(entry.orderId())) continue;
			for (ReportCourseAllocation course : entry.courses()) {
				if (!in.criteria().courseId().equals(course.courseId())) continue;
				for (ReportLineAllocation line : course.lines()) {
					if (!requiredLineById.containsKey(line.lineId()) || !line.hasBillingMonth()
							|| line.billingYear() != in.criteria().billingMonth().getYear()
							|| line.billingMonth() != in.criteria().billingMonth().getMonthValue()) continue;
					coveredByOrder.merge(entry.orderId(), line.amount(), BigDecimal::add);
				}
			}
		}

		Map<UUID, Integer> attendance = attendanceCounts(in.attendanceFacts(), in.zone(), in.criteria().billingMonth());
		Map<UUID, UnpaidStudentProfile> profiles = new HashMap<>();
		in.profiles().forEach(profile -> profiles.put(profile.userId(), profile));
		List<UnpaidStudentRow> rows = new ArrayList<>();
		for (UUID studentId : participantIds) {
			boolean paid = false;
			for (Map.Entry<UUID, UUID> order : studentByOrder.entrySet()) {
				if (!studentId.equals(order.getValue())) continue;
				BigDecimal required = requiredByOrder.get(order.getKey());
				if (required == null) continue;
				BigDecimal covered = coveredByOrder.getOrDefault(order.getKey(), BigDecimal.ZERO);
				if (required.signum() <= 0 || covered.compareTo(required) >= 0) {
					paid = true;
					break;
				}
			}
			if (paid) continue;
			int attended = attendance.getOrDefault(studentId, 0);
			if (in.criteria().atLeastOneAttended() && attended == 0) continue;
			UnpaidStudentProfile profile = profiles.get(studentId);
			rows.add(new UnpaidStudentRow(profile == null ? null : profile.studentProfileId(), studentId,
					clean(profile == null ? null : profile.indexNumber()),
					profile == null || clean(profile.name()).isEmpty() ? "Unknown student" : clean(profile.name()),
					clean(profile == null ? null : profile.email()), clean(profile == null ? null : profile.mobile()), attended));
		}
		rows.sort(ROW_ORDER);
		YearMonth month = in.criteria().billingMonth();
		String monthLabel = month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + month.getYear();
		UnpaidStudentsReport report = new UnpaidStudentsReport(null, null, in.generatedAt(), in.zone().getId(),
				new UnpaidStudentsReport.Institute(in.instituteName()), in.course(),
				new UnpaidStudentsReport.Filters(month, monthLabel, in.criteria().atLeastOneAttended()),
				new UnpaidStudentsReport.EnrollmentBasis("CURRENT_COURSE_PARTICIPATION", in.generatedAt()), rows.size());
		return new Result(report, List.copyOf(rows));
	}

	private static Map<UUID, Integer> attendanceCounts(List<AttendanceReportFact> facts, ZoneId zone, YearMonth month) {
		Map<UUID, Set<String>> sessions = new LinkedHashMap<>();
		Set<UUID> records = new HashSet<>();
		for (AttendanceReportFact fact : facts) {
			if (fact == null || fact.recordId() == null || !records.add(fact.recordId()) || fact.studentId() == null
					|| fact.occurredAt() == null) continue;
			String status = fact.status() == null ? "" : fact.status().toUpperCase(Locale.ROOT);
			if (!"PRESENT".equals(status) && !"LATE".equals(status)) continue;
			LocalDate date = fact.occurredAt().atZone(zone).toLocalDate();
			if (!YearMonth.from(date).equals(month)) continue;
			String grain = fact.sheetId() == null ? "DATE:" + date : "SHEET:" + fact.sheetId();
			sessions.computeIfAbsent(fact.studentId(), ignored -> new HashSet<>()).add(grain);
		}
		Map<UUID, Integer> counts = new HashMap<>();
		sessions.forEach((student, values) -> counts.put(student, values.size()));
		return counts;
	}

	private static String clean(String value) {
		return value == null || value.isBlank() ? "" : value.trim();
	}

}
