package com.lms.tutemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.paymentmanagement.api.OrderLineSnapshot;
import com.lms.tutemanagement.domain.FulfillmentMethod;
import com.lms.tutemanagement.domain.TutePaymentType;
import com.lms.tutemanagement.service.TutePaymentMatcher.TuteRule;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** TUTE-01 section 5 matcher per payment type, timing and course. */
class TutePaymentMatcherTest {

	private static final UUID COURSE = UUID.randomUUID();

	private static final UUID OTHER_COURSE = UUID.randomUUID();

	private static final UUID LESSON = UUID.randomUUID();

	private static final Instant CREATED = Instant.parse("2026-11-10T00:00:00Z");

	private static final YearMonth NOV = YearMonth.of(2026, 11);

	private static TuteRule rule(TutePaymentType type, UUID lesson) {
		return new TuteRule(COURSE, type, lesson, CREATED, NOV);
	}

	private static OrderLineSnapshot monthly(UUID course, int year, int month) {
		return line("MONTHLY", course, null, null, year, month);
	}

	private static OrderLineSnapshot line(String scope, UUID course, UUID module, UUID lesson, Integer year,
			Integer month) {
		return new OrderLineSnapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, scope, course, module,
				lesson, year, month, scope + ":x", "label", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, "LKR");
	}

	private static final Instant AFTER = CREATED.plusSeconds(60);

	@Test
	void coveredByClassFeesMatchesMonthlyFromTheCreationMonthModulesAndLegacyOrders() {
		TuteRule covered = rule(TutePaymentType.COVERED_BY_CLASS_FEES, null);
		assertThat(TutePaymentMatcher.matches(covered, List.of(monthly(COURSE, 2026, 11)), null, AFTER)).isTrue();
		assertThat(TutePaymentMatcher.matches(covered, List.of(monthly(COURSE, 2027, 1)), null, AFTER)).isTrue();
		assertThat(TutePaymentMatcher.matches(covered, List.of(monthly(COURSE, 2026, 10)), null, AFTER))
			.as("period before the creation month").isFalse();
		assertThat(TutePaymentMatcher.matches(covered,
				List.of(line("MODULE", COURSE, UUID.randomUUID(), null, null, null)), null, AFTER)).isTrue();
		assertThat(TutePaymentMatcher.matches(covered, List.of(line("LESSON", COURSE, null, LESSON, null, null)), null,
				AFTER)).as("LESSON lines never cover class fees").isFalse();
		assertThat(TutePaymentMatcher.matches(covered, List.of(), COURSE, AFTER)).as("legacy order").isTrue();
		assertThat(TutePaymentMatcher.matches(covered, List.of(), OTHER_COURSE, AFTER)).isFalse();
	}

	@Test
	void mixedCourseOrderMatchesOnlyTheTutesCourse() {
		TuteRule covered = rule(TutePaymentType.COVERED_BY_CLASS_FEES, null);
		assertThat(TutePaymentMatcher.matches(covered, List.of(monthly(OTHER_COURSE, 2026, 12)), null, AFTER))
			.isFalse();
		assertThat(TutePaymentMatcher.matches(covered,
				List.of(monthly(OTHER_COURSE, 2026, 12), monthly(COURSE, 2026, 12)), null, AFTER)).isTrue();
	}

	@Test
	void paidMatchesOnlyTheLinkedSessionLine() {
		TuteRule linked = rule(TutePaymentType.PAID, LESSON);
		assertThat(TutePaymentMatcher.matches(linked, List.of(line("LESSON", COURSE, null, LESSON, null, null)), null,
				AFTER)).isTrue();
		assertThat(TutePaymentMatcher.matches(linked,
				List.of(line("LESSON", COURSE, null, UUID.randomUUID(), null, null)), null, AFTER)).isFalse();
		assertThat(TutePaymentMatcher.matches(linked, List.of(line("LESSON", OTHER_COURSE, null, LESSON, null, null)),
				null, AFTER)).as("same lesson id on another course").isFalse();
		assertThat(TutePaymentMatcher.matches(linked, List.of(monthly(COURSE, 2026, 12)), null, AFTER)).isFalse();
		assertThat(TutePaymentMatcher.matches(linked, List.of(), COURSE, AFTER)).isFalse();
		TuteRule unlinked = rule(TutePaymentType.PAID, null);
		assertThat(TutePaymentMatcher.matches(unlinked, List.of(line("LESSON", COURSE, null, LESSON, null, null)), null,
				AFTER)).isFalse();
	}

	@Test
	void freeNeverMatchesAndOnlyConfirmedPaymentsAfterCreationCount() {
		assertThat(TutePaymentMatcher.matches(rule(TutePaymentType.FREE, null), List.of(monthly(COURSE, 2026, 12)),
				COURSE, AFTER)).isFalse();
		TuteRule covered = rule(TutePaymentType.COVERED_BY_CLASS_FEES, null);
		assertThat(TutePaymentMatcher.matches(covered, List.of(), COURSE, CREATED)).as("exactly at creation").isTrue();
		assertThat(TutePaymentMatcher.matches(covered, List.of(), COURSE, CREATED.minusSeconds(1)))
			.as("confirmed before the tute existed").isFalse();
		assertThat(TutePaymentMatcher.matches(covered, List.of(), COURSE, null))
			.as("pending / rejected payments have no confirmed_at").isFalse();
	}

	@Test
	void postageSelectionAloneDecidesTheMethod() {
		assertThat(TutePaymentMatcher.methodFor(true)).isEqualTo(FulfillmentMethod.POSTAL);
		assertThat(TutePaymentMatcher.methodFor(false)).isEqualTo(FulfillmentMethod.COLLECTION);
	}

}
