package com.lms.reportinganalytics.service;

import com.lms.attendancemanagement.api.AttendanceReportFact;
import com.lms.enrollmentmanagement.api.CourseSummaryEnrollmentReportApi.FreeCardFact;
import com.lms.enrollmentmanagement.api.CourseSummaryEnrollmentReportApi.ParticipationFact;
import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry.ReportCourseAllocation;
import com.lms.ledgersettlementmanagement.api.TeacherIncomeLedgerApi.ShareRate;
import com.lms.liveclassmanagement.api.CourseSessionReportApi.SessionFact;
import com.lms.paymentmanagement.api.OrderLineSnapshot;
import com.lms.reportinganalytics.service.CourseSummaryReport.CumulativeRow;
import com.lms.reportinganalytics.service.CourseSummaryReport.Filters;
import com.lms.reportinganalytics.service.CourseSummaryReport.Institute;
import com.lms.reportinganalytics.service.CourseSummaryReport.Kpis;
import com.lms.reportinganalytics.service.CourseSummaryReport.WeeklyRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pure RPT-05 aggregation. Sources are independently fetched and deduplicated before combining. */
public final class CourseSummaryCalculator {

	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
	private static final BigDecimal HUNDRED = new BigDecimal("100");
	private static final Set<PaymentMethod> NON_CASH = Set.of(PaymentMethod.FREE, PaymentMethod.STAFF_GRANTED);

	public record Input(CourseSummaryCriteria criteria, ZoneId zone, Instant generatedAt, String instituteName,
			String currency, CourseSummaryReport.TeacherRef teacher, CourseSummaryReport.CourseRef course,
			List<ParticipationFact> participation, List<FreeCardFact> freeCards, List<SessionFact> sessions,
			List<AttendanceReportFact> attendance, List<ReportLedgerEntry> ledger, Map<UUID, UUID> studentByOrder,
			List<OrderLineSnapshot> lines, List<ShareRate> shareRates) {
	}

	private CourseSummaryCalculator() {
	}

	public static CourseSummaryReport calculate(Input input) {
		Map<Integer, Acc> weeks = new LinkedHashMap<>();
		Map<UUID, OrderLineSnapshot> lines = new HashMap<>();
		input.lines().forEach(line -> lines.put(line.lineId(), line));
		Set<UUID> participatingStudents = new HashSet<>();
		Set<UUID> seenParticipation = new HashSet<>();
		for (ParticipationFact fact : input.participation()) {
			if (fact == null || fact.participationId() == null || !seenParticipation.add(fact.participationId())) continue;
			participatingStudents.add(fact.studentId());
			week(weeks, day(fact.enrolledAt(), input.zone(), input.criteria())).registrations.add(fact.studentId());
		}
		Set<UUID> sessions = new HashSet<>();
		for (SessionFact fact : input.sessions()) {
			if (fact != null && fact.sessionId() != null && sessions.add(fact.sessionId())) {
				week(weeks, day(fact.scheduledStart(), input.zone(), input.criteria())).classes++;
			}
		}
		Set<UUID> attendance = new HashSet<>();
		for (AttendanceReportFact fact : input.attendance()) {
			if (fact != null && fact.recordId() != null && attendance.add(fact.recordId()) && present(fact.status())) {
				week(weeks, day(fact.occurredAt(), input.zone(), input.criteria())).attendance++;
			}
		}
		Map<UUID, List<ShareRate>> rates = new HashMap<>();
		for (ShareRate rate : input.shareRates()) rates.computeIfAbsent(rate.teacherId(), ignored -> new ArrayList<>()).add(rate);
		rates.values().forEach(list -> list.sort(Comparator.comparing(ShareRate::effectiveFrom)));
		Map<String, Category> categories = new LinkedHashMap<>();
		categories.put("Full Cards", new Category());
		categories.put("Discounted Cards", new Category());
		Set<UUID> paidStudents = new HashSet<>();
		Set<UUID> seenEntries = new HashSet<>();
		BigDecimal totalRevenue = ZERO, totalCommission = ZERO, totalTeacherShare = ZERO;
		boolean missingRate = false;
		for (ReportLedgerEntry entry : input.ledger()) {
			if (entry == null || entry.entryId() == null || !seenEntries.add(entry.entryId()) || NON_CASH.contains(entry.method())) continue;
			for (ReportCourseAllocation allocation : entry.courses()) {
				if (!input.criteria().courseId().equals(allocation.courseId())) continue;
				BigDecimal amount = money(allocation.amount());
				BigDecimal rate = allocation.settlement() == null ? rateOn(rates.get(input.criteria().teacherId()),
						LocalDate.ofInstant(entry.createdAt(), input.zone())) : allocation.settlement().sharePercent();
				BigDecimal share = rate == null ? ZERO : amount.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
				BigDecimal commission = rate == null ? ZERO : amount.subtract(share);
				int number = day(entry.createdAt(), input.zone(), input.criteria());
				Acc weekly = week(weeks, number);
				weekly.revenue = weekly.revenue.add(amount);
				weekly.commission = weekly.commission.add(commission);
				weekly.teacherShare = weekly.teacherShare.add(share);
				if (!entry.refund() && amount.signum() > 0) {
					weekly.payments.add(entry.entryId());
					UUID student = input.studentByOrder().get(entry.orderId());
					if (student != null) {
						paidStudents.add(student);
					}
				}
				totalRevenue = totalRevenue.add(amount); totalCommission = totalCommission.add(commission); totalTeacherShare = totalTeacherShare.add(share);
				if (rate == null) missingRate = true;
				List<OrderLineSnapshot> allocationLines = allocation.lines().stream()
					.map(lineAllocation -> lines.get(lineAllocation.lineId()))
					.filter(java.util.Objects::nonNull)
					.filter(line -> !line.reward())
					.toList();
				boolean discounted = allocationLines.stream()
					.anyMatch(line -> line.discountAmount() != null && line.discountAmount().signum() > 0);
				Category category = categories.get(discounted ? "Discounted Cards" : "Full Cards");
				if (!entry.refund() && amount.signum() > 0) {
					BigDecimal listValue = allocationLines.isEmpty() ? amount : allocationLines.stream()
						.map(OrderLineSnapshot::unitAmount)
						.filter(java.util.Objects::nonNull)
						.map(CourseSummaryCalculator::money)
						.reduce(ZERO, BigDecimal::add);
					category.cards.add(entry.entryId());
					category.unitPrices.add(listValue);
					category.grossListValue = category.grossListValue.add(listValue);
				}
				category.commission = category.commission.add(commission);
				category.teacherShare = category.teacherShare.add(share);
			}
		}
		Category free = new Category(); Set<UUID> cards = new HashSet<>();
		for (FreeCardFact card : input.freeCards()) if (card != null && card.cardId() != null && cards.add(card.cardId()) && !card.revoked()) free.cards.add(card.studentId());
		List<WeeklyRow> weekly = new ArrayList<>();
		LocalDate cursor = input.criteria().startDate(); int n = 1;
		while (!cursor.isAfter(input.criteria().endDate())) {
			LocalDate ending = cursor.plusDays(6).isAfter(input.criteria().endDate()) ? input.criteria().endDate() : cursor.plusDays(6);
			Acc acc = weeks.getOrDefault(n, new Acc());
			weekly.add(new WeeklyRow(n, cursor.getDayOfMonth() + "-" + ending.getDayOfMonth(), acc.registrations.size(), acc.classes,
					acc.attendance, acc.payments.size(), CourseSummaryReport.money(acc.revenue), CourseSummaryReport.money(acc.commission), CourseSummaryReport.money(acc.teacherShare)));
			cursor = cursor.plusDays(7); n++;
		}
		List<CumulativeRow> cumulative = List.of(row("Full Cards", categories.get("Full Cards")), row("Discounted Cards", categories.get("Discounted Cards")),
				new CumulativeRow("Free Card Students", free.cards.size(), "—", "—", "—", "—", true),
				new CumulativeRow("Not Paid Full Cards", 0, "—", "—", "—", "—", false));
		String commissionPercent = missingRate || totalRevenue.signum() == 0 ? "—"
				: totalCommission.multiply(HUNDRED).divide(totalRevenue, 2, RoundingMode.HALF_UP).toPlainString() + "%";
		Kpis kpis = new Kpis(participatingStudents.size(), input.participation().size(),
				weekly.stream().mapToInt(row -> row.payments()).sum(), commissionPercent, sessions.size(), attendance.size(),
				CourseSummaryReport.money(totalRevenue), CourseSummaryReport.money(totalCommission), CourseSummaryReport.money(totalTeacherShare));
		return new CourseSummaryReport(null, null, input.generatedAt(), input.zone().getId(), new Institute(input.instituteName(), input.currency()), input.teacher(), input.course(), input.criteria().reportName(),
				new Filters(input.criteria().teacherId(), input.criteria().courseId(), input.criteria().startDate(), input.criteria().endDate(), input.criteria().month().toString(), input.criteria().reportName()),
				kpis, weekly, cumulative, List.of("Revenue is confirmed course allocation net of refunds and discounts; facility fees and postage are excluded.", "Free Cards are complimentary access, never received revenue.", "Not Paid Full Cards is not tracked by an authoritative historical source."));
	}

	private static CumulativeRow row(String name, Category value) {
		String unitPrice = value.unitPrices.size() == 1 ? CourseSummaryReport.money(value.unitPrices.iterator().next()) : "Varies";
		return new CumulativeRow(name, value.cards.size(), unitPrice, CourseSummaryReport.money(value.grossListValue),
				CourseSummaryReport.money(value.commission), CourseSummaryReport.money(value.teacherShare), true);
	}
	private static boolean present(String value) { return "PRESENT".equalsIgnoreCase(value) || "LATE".equalsIgnoreCase(value); }
	private static BigDecimal money(BigDecimal value) { return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP); }
	private static BigDecimal commissionShare(BigDecimal whole, BigDecimal total, BigDecimal part) { return total == null || total.signum() == 0 ? ZERO : whole.multiply(part).divide(total, 2, RoundingMode.HALF_UP); }
	private static BigDecimal rateOn(List<ShareRate> rates, LocalDate date) { BigDecimal result = null; for (ShareRate rate : rates == null ? List.<ShareRate>of() : rates) if (!rate.effectiveFrom().isAfter(date)) result = rate.sharePercent(); return result; }
	private static int day(Instant value, ZoneId zone, CourseSummaryCriteria criteria) { return value == null ? 1 : (int) (ChronoUnit.DAYS.between(criteria.startDate(), value.atZone(zone).toLocalDate()) / 7) + 1; }
	private static Acc week(Map<Integer, Acc> weeks, int index) { return weeks.computeIfAbsent(Math.max(1, index), ignored -> new Acc()); }
	private static final class Acc { final Set<UUID> registrations = new HashSet<>(); final Set<UUID> payments = new HashSet<>(); int classes; int attendance; BigDecimal revenue = ZERO, commission = ZERO, teacherShare = ZERO; }

	private static final class Category {

		final Set<UUID> cards = new HashSet<>();
		final Set<BigDecimal> unitPrices = new HashSet<>();
		BigDecimal grossListValue = ZERO;
		BigDecimal commission = ZERO;
		BigDecimal teacherShare = ZERO;

	}

}
