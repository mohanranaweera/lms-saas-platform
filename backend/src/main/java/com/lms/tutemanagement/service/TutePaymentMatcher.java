package com.lms.tutemanagement.service;

import com.lms.paymentmanagement.api.OrderLineSnapshot;
import com.lms.tutemanagement.domain.FulfillmentMethod;
import com.lms.tutemanagement.domain.TutePaymentType;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Collection;
import java.util.UUID;

/**
 * TUTE-01 section 5 matching - pure, so every rule is unit-testable. Inputs are server-read facts
 * of ONE confirmed payment's order (its lines, or the course of a legacy line-less order) and the
 * payment's own {@code confirmed_at}; nothing here reads a request, and nothing here charges,
 * unlocks or writes anything.
 *
 * <ul>
 * <li>Only payments confirmed at/after the tute's {@code created_at} qualify (no backfill).</li>
 * <li>COVERED_BY_CLASS_FEES: a {@code MONTHLY} line of the tute's course billed in a month at or
 * after the tute's creation month (tenant timezone), a {@code MODULE} line of the course, or a
 * legacy (line-less) order of the course. {@code LESSON} lines never qualify.</li>
 * <li>PAID: only when linked to a paid session - the {@code LESSON} line for that lesson of the
 * tute's course (the session price is the only charge).</li>
 * <li>FREE: never (manual assignment only).</li>
 * </ul>
 * Free Cards are not payments and never reach this matcher.
 */
public final class TutePaymentMatcher {

	static final String MONTHLY = "MONTHLY";

	static final String MODULE = "MODULE";

	static final String LESSON = "LESSON";

	private TutePaymentMatcher() {
	}

	/** The tute facts the matcher needs. */
	public record TuteRule(UUID courseId, TutePaymentType paymentType, UUID paidLessonId, Instant createdAt,
			YearMonth creationMonth) {
	}

	/**
	 * @param lines the order's lines (empty for a legacy order).
	 * @param legacyCourseId the course of a legacy (line-less) order, else {@code null}.
	 * @param confirmedAt the payment's {@code confirmed_at}; {@code null} (not confirmed) never matches.
	 */
	public static boolean matches(TuteRule tute, Collection<OrderLineSnapshot> lines, UUID legacyCourseId,
			Instant confirmedAt) {
		if (tute == null || confirmedAt == null || tute.createdAt() == null || confirmedAt.isBefore(tute.createdAt())) {
			return false;
		}
		Collection<OrderLineSnapshot> safeLines = lines == null ? java.util.List.of() : lines;
		return switch (tute.paymentType()) {
			case COVERED_BY_CLASS_FEES -> tute.courseId().equals(legacyCourseId)
					|| safeLines.stream().anyMatch(line -> coversClassFees(tute, line));
			case PAID -> tute.paidLessonId() != null && safeLines.stream()
				.anyMatch(line -> LESSON.equals(line.scope()) && tute.courseId().equals(line.courseId())
						&& tute.paidLessonId().equals(line.lessonId()));
			case FREE -> false;
		};
	}

	private static boolean coversClassFees(TuteRule tute, OrderLineSnapshot line) {
		if (!tute.courseId().equals(line.courseId())) {
			return false;
		}
		if (MODULE.equals(line.scope())) {
			return true;
		}
		if (MONTHLY.equals(line.scope()) && line.periodYear() != null && line.periodMonth() != null) {
			return !YearMonth.of(line.periodYear(), line.periodMonth()).isBefore(tute.creationMonth());
		}
		return false;
	}

	/**
	 * POSTAL only when the order selected postage for the tute's course ({@code postage_selected},
	 * whatever the fee - zero postage still counts); otherwise COLLECTION. A free price or zero
	 * postage fee alone never decides it.
	 */
	public static FulfillmentMethod methodFor(boolean postageSelectedForCourse) {
		return postageSelectedForCourse ? FulfillmentMethod.POSTAL : FulfillmentMethod.COLLECTION;
	}

}
