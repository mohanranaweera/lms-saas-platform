package com.lms.paymentmanagement.discount.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.paymentmanagement.discount.service.DiscountPricing.CartLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** D4 allocation: pro rata, largest remainder in cents, ties to the lower line number, exact sum. */
class DiscountAllocatorTest {

	private static final UUID COURSE = UUID.randomUUID();

	private static CartLine line(int lineNo, String unit) {
		return new CartLine(lineNo, "K" + lineNo, COURSE, "MONTHLY", 2026, 11, new BigDecimal(unit));
	}

	@Test
	void tiesGoToTheLowerLineNumber() {
		Map<Integer, BigDecimal> allocation = DiscountAllocator.allocate(new BigDecimal("0.10"),
				List.of(line(3, "1.00"), line(1, "1.00"), line(2, "1.00")));

		assertThat(allocation).containsExactly(Map.entry(1, new BigDecimal("0.04")),
				Map.entry(2, new BigDecimal("0.03")), Map.entry(3, new BigDecimal("0.03")));
	}

	@Test
	void largestFractionalRemainderGetsTheLeftoverCent() {
		// 100.00 over 1000.00 / 2000.00 / 3000.01 -> exact shares 16.666..., 33.333..., 50.000...
		Map<Integer, BigDecimal> allocation = DiscountAllocator.allocate(new BigDecimal("100.00"),
				List.of(line(1, "1000.00"), line(2, "2000.00"), line(3, "3000.01")));

		assertThat(allocation.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
			.isEqualByComparingTo("100.00");
		assertThat(allocation.get(1)).isEqualByComparingTo("16.67");
		assertThat(allocation.get(2)).isEqualByComparingTo("33.33");
		assertThat(allocation.get(3)).isEqualByComparingTo("50.00");
	}

	@Test
	void fullAmountGivesEveryLineItsUnit() {
		Map<Integer, BigDecimal> allocation = DiscountAllocator.allocate(new BigDecimal("3500.50"),
				List.of(line(1, "1000.25"), line(2, "2500.25")));

		assertThat(allocation.get(1)).isEqualByComparingTo("1000.25");
		assertThat(allocation.get(2)).isEqualByComparingTo("2500.25");
	}

	@Test
	void zeroTotalAllocatesNothing() {
		assertThat(DiscountAllocator.allocate(new BigDecimal("0.00"), List.of(line(1, "10.00")))).isEmpty();
	}

	@Test
	void linesReceivingZeroAreOmitted() {
		Map<Integer, BigDecimal> allocation = DiscountAllocator.allocate(new BigDecimal("0.01"),
				List.of(line(1, "5.00"), line(2, "5.00")));

		assertThat(allocation).containsExactly(Map.entry(1, new BigDecimal("0.01")));
	}

	@Test
	void totalAboveTheLinesIsRejected() {
		assertThatThrownBy(() -> DiscountAllocator.allocate(new BigDecimal("10.01"), List.of(line(1, "10.00"))))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void randomizedAllocationsSumExactlyAndNeverExceedAUnit() {
		Random random = new Random(42);
		for (int run = 0; run < 500; run++) {
			List<CartLine> lines = new ArrayList<>();
			long sumCents = 0;
			int count = 1 + random.nextInt(8);
			for (int i = 1; i <= count; i++) {
				long cents = 1 + random.nextInt(500_000);
				sumCents += cents;
				lines.add(line(i, BigDecimal.valueOf(cents, 2).toPlainString()));
			}
			BigDecimal total = BigDecimal.valueOf((long) (random.nextDouble() * sumCents), 2);
			Map<Integer, BigDecimal> allocation = DiscountAllocator.allocate(total, lines);

			assertThat(allocation.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
				.isEqualByComparingTo(total);
			for (CartLine line : lines) {
				assertThat(allocation.getOrDefault(line.lineNo(), BigDecimal.ZERO))
					.isLessThanOrEqualTo(line.unitAmount());
			}
		}
	}

}
