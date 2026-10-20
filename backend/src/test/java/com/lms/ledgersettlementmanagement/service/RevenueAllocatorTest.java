package com.lms.ledgersettlementmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.ledgersettlementmanagement.api.LedgerRevenueAllocation;
import com.lms.paymentmanagement.api.OrderRevenueBasis;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ADR-029 allocation rules (pure unit tests). */
class RevenueAllocatorTest {

	private static final UUID LOW = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final UUID HIGH = UUID.fromString("00000000-0000-0000-0000-000000000002");

	private static final UUID THIRD = UUID.fromString("00000000-0000-0000-0000-000000000003");

	private static BigDecimal d(String value) {
		return new BigDecimal(value);
	}

	private static OrderRevenueBasis lineOrder(String orderAmount, Object... courseAndShare) {
		Map<UUID, BigDecimal> shares = new LinkedHashMap<>();
		for (int i = 0; i < courseAndShare.length; i += 2) {
			shares.put((UUID) courseAndShare[i], d((String) courseAndShare[i + 1]));
		}
		return new OrderRevenueBasis(UUID.randomUUID(), d(orderAmount), null, shares);
	}

	private static Map<UUID, BigDecimal> byCourse(List<LedgerRevenueAllocation> allocations) {
		Map<UUID, BigDecimal> map = new LinkedHashMap<>();
		allocations.forEach(a -> map.put(a.courseId(), a.amount()));
		return map;
	}

	private static BigDecimal total(List<LedgerRevenueAllocation> allocations) {
		return allocations.stream().map(LedgerRevenueAllocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	@Test
	void legacyOrderAllocatesTheFullAmountToItsCourse() {
		OrderRevenueBasis legacy = new OrderRevenueBasis(UUID.randomUUID(), d("5000.00"), LOW, Map.of());
		assertThat(RevenueAllocator.allocate(d("5000.00"), legacy))
			.containsExactly(new LedgerRevenueAllocation(LOW, d("5000.00")));
		assertThat(RevenueAllocator.allocate(d("-1200.00"), legacy))
			.containsExactly(new LedgerRevenueAllocation(LOW, d("-1200.00")));
		assertThat(RevenueAllocator.allocate(d("10.00"),
				new OrderRevenueBasis(UUID.randomUUID(), d("10.00"), null, Map.of()))).isEmpty();
		assertThat(RevenueAllocator.allocate(d("10.00"), null)).isEmpty();
	}

	@Test
	void confirmedPaymentGivesEachCourseExactlyItsLineTotalsAndNeverPostage() {
		// A 7100 (2 months + fee), B 4000, postage 200 -> order 11300.
		OrderRevenueBasis basis = lineOrder("11300.00", HIGH, "7100.00", LOW, "4000.00");
		List<LedgerRevenueAllocation> allocations = RevenueAllocator.allocate(d("11300.00"), basis);
		assertThat(byCourse(allocations)).containsExactly(Map.entry(LOW, d("4000.00")), Map.entry(HIGH, d("7100.00")));
		assertThat(total(allocations)).isEqualByComparingTo("11100.00");

		// Single-course line order with postage: postage never reaches the course.
		assertThat(RevenueAllocator.allocate(d("3800.00"), lineOrder("3800.00", LOW, "3600.00")))
			.containsExactly(new LedgerRevenueAllocation(LOW, d("3600.00")));
	}

	@Test
	void refundsAreSplitProRataAndAFullRefundReversesTheSharesExactly() {
		OrderRevenueBasis basis = lineOrder("7800.00", LOW, "3600.00", HIGH, "4000.00");
		assertThat(byCourse(RevenueAllocator.allocate(d("-780.00"), basis)))
			.containsExactly(Map.entry(LOW, d("-360.00")), Map.entry(HIGH, d("-400.00")));
		assertThat(byCourse(RevenueAllocator.allocate(d("-7800.00"), basis)))
			.containsExactly(Map.entry(LOW, d("-3600.00")), Map.entry(HIGH, d("-4000.00")));
	}

	@Test
	void roundingRemainderGoesToTheLargestShareThenLowestCourseId() {
		// Three equal shares of 100, no postage, refund 100.00: 33.33 x 3 = 99.99,
		// the 0.01 remainder goes to the lowest course id (all tied).
		OrderRevenueBasis equal = lineOrder("300.00", THIRD, "100.00", HIGH, "100.00", LOW, "100.00");
		List<LedgerRevenueAllocation> split = RevenueAllocator.allocate(d("-100.00"), equal);
		assertThat(byCourse(split)).containsExactly(Map.entry(LOW, d("-33.34")), Map.entry(HIGH, d("-33.33")),
				Map.entry(THIRD, d("-33.33")));
		assertThat(total(split)).isEqualByComparingTo("-100.00");

		// Unequal shares: the largest share takes the remainder.
		OrderRevenueBasis unequal = lineOrder("300.00", LOW, "100.00", HIGH, "200.00");
		List<LedgerRevenueAllocation> parts = RevenueAllocator.allocate(d("100.01"), unequal);
		assertThat(total(parts)).isEqualByComparingTo("100.01");
		assertThat(byCourse(parts)).containsExactly(Map.entry(LOW, d("33.34")), Map.entry(HIGH, d("66.67")));

		// Attributable total excludes the postage portion and still sums exactly.
		OrderRevenueBasis withPostage = lineOrder("1000.00", LOW, "333.33", HIGH, "566.67");
		List<LedgerRevenueAllocation> refund = RevenueAllocator.allocate(d("-77.77"), withPostage);
		assertThat(total(refund)).isEqualByComparingTo("-69.99");
	}

}
