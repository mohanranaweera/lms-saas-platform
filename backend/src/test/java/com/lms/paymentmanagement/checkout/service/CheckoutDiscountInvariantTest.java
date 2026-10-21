package com.lms.paymentmanagement.checkout.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.paymentmanagement.discount.domain.DiscountType;
import com.lms.paymentmanagement.discount.domain.DiscountValueType;
import com.lms.paymentmanagement.discount.domain.SelectionMode;
import com.lms.paymentmanagement.discount.service.DiscountPricing.AppliedDiscount;
import com.lms.paymentmanagement.discount.service.DiscountPricing.Qualification;
import com.lms.paymentmanagement.discount.service.RuleSnapshot;
import com.lms.paymentmanagement.order.domain.StudentOrderLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * DISC-01: the placement-time invariant (order discount = sum of its lines'
 * discounts; each applied discount = sum of the lines linked to its snapshot)
 * fails closed on any mismatch.
 */
class CheckoutDiscountInvariantTest {

	private final UUID ruleId = UUID.randomUUID();

	private final UUID snapshotId = UUID.randomUUID();

	private AppliedDiscount applied(String amount) {
		RuleSnapshot rule = new RuleSnapshot(ruleId, UUID.randomUUID(), DiscountType.BUNDLE_AMOUNT_OFF, "Bundle", null,
				SelectionMode.ANY, null, DiscountValueType.FIXED, new BigDecimal(amount), null, LocalDate.of(2026, 1, 1),
				LocalDate.of(2026, 12, 31), null, null, "LKR", List.of(UUID.randomUUID()), List.of());
		return new AppliedDiscount(rule, new BigDecimal(amount), Map.of(1, new BigDecimal(amount)),
				new Qualification(List.of(), List.of(), List.of(), List.of()));
	}

	private static StudentOrderLine line(int no, String unit, String discount, UUID orderDiscountId) {
		return new StudentOrderLine(UUID.randomUUID(), UUID.randomUUID(), no, "LESSON", UUID.randomUUID(), null,
				UUID.randomUUID(), null, null, null, "Lesson " + no, new BigDecimal(unit), BigDecimal.ZERO.setScale(2),
				new BigDecimal(discount), "LKR", "LESSON:" + no, Instant.now(), orderDiscountId, false);
	}

	@Test
	void consistentLinesPass() {
		assertThatCode(() -> CheckoutComposerService.requireDiscountInvariants(new BigDecimal("100.00"),
				List.of(applied("100.00")),
				List.of(line(1, "1000.00", "60.00", snapshotId), line(2, "500.00", "40.00", snapshotId),
						line(3, "200.00", "0.00", null)),
				Map.of(ruleId, snapshotId)))
			.doesNotThrowAnyException();
	}

	@Test
	void anOrderDiscountThatIsNotTheSumOfItsLinesFailsClosed() {
		assertThatThrownBy(() -> CheckoutComposerService.requireDiscountInvariants(new BigDecimal("100.00"),
				List.of(applied("100.00")), List.of(line(1, "1000.00", "99.99", snapshotId)), Map.of(ruleId, snapshotId)))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aSnapshotAmountThatIsNotTheSumOfItsLinkedLinesFailsClosed() {
		UUID otherSnapshot = UUID.randomUUID();
		assertThatThrownBy(() -> CheckoutComposerService.requireDiscountInvariants(new BigDecimal("100.00"),
				List.of(applied("100.00")),
				List.of(line(1, "1000.00", "60.00", snapshotId), line(2, "500.00", "40.00", otherSnapshot)),
				Map.of(ruleId, snapshotId)))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> CheckoutComposerService.requireDiscountInvariants(new BigDecimal("100.00"),
				List.of(applied("100.00")), List.of(line(1, "1000.00", "100.00", snapshotId)), Map.of()))
			.isInstanceOf(IllegalStateException.class);
	}

}
