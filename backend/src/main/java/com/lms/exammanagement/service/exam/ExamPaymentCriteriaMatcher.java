package com.lms.exammanagement.service.exam;

import com.lms.exammanagement.service.exam.ExamViews.PaymentCriteriaView;
import com.lms.paymentmanagement.api.OrderLineSnapshot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ADR-032 criteria matching for payment auto-enrollment - pure, so every criteria kind is
 * unit-testable. Inputs are server-read facts only (order lines of a CONFIRMED payment,
 * lesson session dates, the student's cumulative confirmed cash for the course); nothing here
 * reads a request.
 *
 * <ul>
 * <li>MONTHS: a {@code MONTHLY} line of the exam's course billed in a criteria month (reward /
 * discounted lines count: they are entitlements). Free Cards qualify through {@link
 * #freeCardCovers} (ADR-030), never through amounts.</li>
 * <li>MODULES: a {@code MODULE} line of the course for a criteria module.</li>
 * <li>DATES: a {@code LESSON} line of the course whose session date (tenant timezone) is a
 * criteria date.</li>
 * <li>INSTALLMENT: the order pays something towards the course and the student's cumulative
 * confirmed cash for the course (course shares - never facility fees, postage or Free Cards)
 * reaches {@code minimumAmount}.</li>
 * <li>FULL_FEE: the order is the course's full-fee (legacy single-course) order.</li>
 * </ul>
 */
public final class ExamPaymentCriteriaMatcher {

	public static final String MONTHS = "MONTHS";

	public static final String MODULES = "MODULES";

	public static final String DATES = "DATES";

	public static final String INSTALLMENT = "INSTALLMENT";

	public static final String FULL_FEE = "FULL_FEE";

	private static final String MONTHLY_SCOPE = "MONTHLY";

	private static final String MODULE_SCOPE = "MODULE";

	private static final String LESSON_SCOPE = "LESSON";

	private ExamPaymentCriteriaMatcher() {
	}

	/**
	 * The facts of one confirmed payment's order.
	 * @param lines the order's lines (empty for a legacy single-course order).
	 * @param legacyCourseId the course of a legacy (line-less) order, else {@code null}.
	 * @param lessonDates session date (tenant timezone) of every lesson referenced by a LESSON line.
	 * @param cumulativeCourseCash the student's total confirmed cash for the exam's course,
	 * including this payment; {@code null} when not needed.
	 */
	public record OrderFacts(Collection<OrderLineSnapshot> lines, UUID legacyCourseId,
			Map<UUID, LocalDate> lessonDates, BigDecimal cumulativeCourseCash) {
	}

	public static boolean matches(PaymentCriteriaView criteria, UUID examCourseId, OrderFacts facts) {
		if (criteria == null || criteria.kind() == null) {
			return false;
		}
		return switch (criteria.kind()) {
			case MONTHS -> {
				Set<YearMonth> months = months(criteria);
				yield facts.lines().stream()
					.anyMatch(line -> MONTHLY_SCOPE.equals(line.scope()) && examCourseId.equals(line.courseId())
							&& line.periodYear() != null && line.periodMonth() != null
							&& months.contains(YearMonth.of(line.periodYear(), line.periodMonth())));
			}
			case MODULES -> {
				Set<UUID> modules = criteria.moduleIds() == null ? Set.of() : Set.copyOf(criteria.moduleIds());
				yield facts.lines().stream()
					.anyMatch(line -> MODULE_SCOPE.equals(line.scope()) && examCourseId.equals(line.courseId())
							&& line.moduleId() != null && modules.contains(line.moduleId()));
			}
			case DATES -> {
				Set<LocalDate> dates = dates(criteria);
				yield facts.lines().stream()
					.anyMatch(line -> LESSON_SCOPE.equals(line.scope()) && examCourseId.equals(line.courseId())
							&& line.lessonId() != null && dates.contains(facts.lessonDates().get(line.lessonId())));
			}
			case INSTALLMENT -> {
				boolean paysCourse = examCourseId.equals(facts.legacyCourseId())
						|| facts.lines().stream().anyMatch(line -> examCourseId.equals(line.courseId()));
				yield paysCourse && criteria.minimumAmount() != null && facts.cumulativeCourseCash() != null
						&& facts.cumulativeCourseCash().compareTo(criteria.minimumAmount()) >= 0;
			}
			case FULL_FEE -> examCourseId.equals(facts.legacyCourseId());
			default -> false;
		};
	}

	/** ADR-030 / D5: an unrevoked Free Card whose validity overlaps a criteria month (MONTHS only). */
	public static boolean freeCardCovers(PaymentCriteriaView criteria, LocalDate validFrom, LocalDate validTo,
			boolean revoked) {
		if (revoked || criteria == null || !MONTHS.equals(criteria.kind())) {
			return false;
		}
		for (YearMonth month : months(criteria)) {
			if (!validFrom.isAfter(month.atEndOfMonth()) && !validTo.isBefore(month.atDay(1))) {
				return true;
			}
		}
		return false;
	}

	/** Kinds that need the student's cumulative course cash. */
	public static boolean needsCumulativeCash(PaymentCriteriaView criteria) {
		return criteria != null && INSTALLMENT.equals(criteria.kind());
	}

	public static Set<YearMonth> months(PaymentCriteriaView criteria) {
		if (criteria.months() == null) {
			return Set.of();
		}
		return criteria.months().stream().map(YearMonth::parse).collect(Collectors.toSet());
	}

	private static Set<LocalDate> dates(PaymentCriteriaView criteria) {
		if (criteria.dates() == null) {
			return Set.of();
		}
		return criteria.dates().stream().map(LocalDate::parse).collect(Collectors.toSet());
	}

}
