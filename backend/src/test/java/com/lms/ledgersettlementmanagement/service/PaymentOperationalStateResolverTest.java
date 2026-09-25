package com.lms.ledgersettlementmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.ledgersettlementmanagement.api.PaymentOperationalState;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * One test per {@code PaymentOperationalState} transition case in plan §8 -
 * a pure-function unit test, no Spring/Mockito needed (see {@link
 * PaymentOperationalStateResolver}'s own javadoc).
 */
class PaymentOperationalStateResolverTest {

	@Test
	void aFreshlyPlacedOrderWithNoPaymentAttemptIsUnpaid() {
		PaymentOperationalState state = PaymentOperationalStateResolver.resolve(null, null, false, false, false,
				false);

		assertThat(state).isEqualTo(PaymentOperationalState.UNPAID);
	}

	@Test
	void aPendingPaymentWithNoSlipIsPending() {
		PaymentOperationalState state = PaymentOperationalStateResolver.resolve(null, null, true, false, false,
				false);

		assertThat(state).isEqualTo(PaymentOperationalState.PENDING);
	}

	@Test
	void aSubmittedSlipIsUnderReview() {
		PaymentOperationalState state = PaymentOperationalStateResolver.resolve(null, null, false, false, true,
				false);

		assertThat(state).isEqualTo(PaymentOperationalState.UNDER_REVIEW);
	}

	@Test
	void anUnderReviewSlipIsUnderReview() {
		// hasOpenSlip covers BOTH SUBMITTED and UNDER_REVIEW - see
		// OrderSlipDetail's javadoc - so this is the same input as the
		// SUBMITTED case above, included for explicit plan §8 traceability.
		PaymentOperationalState state = PaymentOperationalStateResolver.resolve(null, null, false, false, true,
				false);

		assertThat(state).isEqualTo(PaymentOperationalState.UNDER_REVIEW);
	}

	@Test
	void aConfirmedPaymentWithNoRefundIsPaid() {
		PaymentOperationalState state = PaymentOperationalStateResolver.resolve(new BigDecimal("100.00"),
				BigDecimal.ZERO, false, false, false, false);

		assertThat(state).isEqualTo(PaymentOperationalState.PAID);
	}

	/**
	 * Regression coverage for a real bug found and fixed during
	 * implementation: a genuinely CONFIRMED FREE-course payment (ADR-015/
	 * V42) carries {@code amount == 0.00}, so checking {@code
	 * confirmedTotal.signum() > 0} (magnitude) instead of {@code
	 * confirmedTotal != null} (presence) would wrongly classify a real,
	 * active FREE enrollment as {@code UNPAID}.
	 */
	@Test
	void aConfirmedZeroAmountFreeCoursePaymentWithNoRefundIsPaidNotUnpaid() {
		PaymentOperationalState state = PaymentOperationalStateResolver.resolve(BigDecimal.ZERO, null, false, false,
				false, false);

		assertThat(state).isEqualTo(PaymentOperationalState.PAID);
	}

	/**
	 * Companion regression case: a {@code refunded >= confirmed} check
	 * without also requiring {@code refunded > 0} would wrongly classify
	 * that same $0-confirmed, never-refunded FREE order as {@code REFUNDED}
	 * (since {@code 0 >= 0}).
	 */
	@Test
	void aConfirmedZeroAmountFreeCoursePaymentWithExplicitZeroRefundedTotalIsPaidNotRefunded() {
		PaymentOperationalState state = PaymentOperationalStateResolver.resolve(BigDecimal.ZERO, BigDecimal.ZERO,
				false, false, false, false);

		assertThat(state).isEqualTo(PaymentOperationalState.PAID);
	}

	@Test
	void aConfirmedPaymentWithNullRefundedTotalIsPaid() {
		PaymentOperationalState state = PaymentOperationalStateResolver.resolve(new BigDecimal("100.00"), null,
				false, false, false, false);

		assertThat(state).isEqualTo(PaymentOperationalState.PAID);
	}

	@Test
	void aRejectedPaymentWithNoLaterSuccessfulAttemptIsRejected() {
		PaymentOperationalState state = PaymentOperationalStateResolver.resolve(null, null, false, true, false,
				false);

		assertThat(state).isEqualTo(PaymentOperationalState.REJECTED);
	}

	@Test
	void aRejectedSlipWithNoLaterSuccessfulAttemptIsRejected() {
		PaymentOperationalState state = PaymentOperationalStateResolver.resolve(null, null, false, false, false,
				true);

		assertThat(state).isEqualTo(PaymentOperationalState.REJECTED);
	}

	@Test
	void refundedAmountEqualToConfirmedAmountIsRefunded() {
		PaymentOperationalState state = PaymentOperationalStateResolver.resolve(new BigDecimal("100.00"),
				new BigDecimal("100.00"), false, false, false, false);

		assertThat(state).isEqualTo(PaymentOperationalState.REFUNDED);
	}

	@Test
	void refundedAmountExceedingConfirmedAmountIsStillRefunded() {
		// Defensive boundary - should never happen given RefundService's own
		// refundable-remainder guard, but the resolver must not misclassify
		// it as PAID if it ever did.
		PaymentOperationalState state = PaymentOperationalStateResolver.resolve(new BigDecimal("100.00"),
				new BigDecimal("150.00"), false, false, false, false);

		assertThat(state).isEqualTo(PaymentOperationalState.REFUNDED);
	}

	/**
	 * The explicit partial-refund boundary test plan §8 calls out by name: a
	 * refund strictly less than the confirmed amount must stay {@code PAID},
	 * never {@code REFUNDED}.
	 */
	@Test
	void aPartialRefundStaysPaid() {
		PaymentOperationalState state = PaymentOperationalStateResolver.resolve(new BigDecimal("100.00"),
				new BigDecimal("40.00"), false, false, false, false);

		assertThat(state).isEqualTo(PaymentOperationalState.PAID);
	}

	@Test
	void aConfirmedPaymentTakesPrecedenceOverAnOpenSlipOrPendingPaymentSignal() {
		// Structurally near-unreachable in production (a CONFIRMED payment
		// and a still-open slip/pending payment for the SAME order should
		// never coexist), but the resolver's precedence must still be
		// deterministic and never regress to UNDER_REVIEW/PENDING if it did.
		PaymentOperationalState state = PaymentOperationalStateResolver.resolve(new BigDecimal("100.00"),
				BigDecimal.ZERO, true, false, true, false);

		assertThat(state).isEqualTo(PaymentOperationalState.PAID);
	}

	@Test
	void anOpenSlipTakesPrecedenceOverAPendingPaymentSignal() {
		PaymentOperationalState state = PaymentOperationalStateResolver.resolve(null, null, true, false, true,
				false);

		assertThat(state).isEqualTo(PaymentOperationalState.UNDER_REVIEW);
	}

	@Test
	void aPendingPaymentTakesPrecedenceOverAPriorRejection() {
		// Retry-after-rejection: the open attempt in progress must win.
		PaymentOperationalState state = PaymentOperationalStateResolver.resolve(null, null, true, true, false,
				false);

		assertThat(state).isEqualTo(PaymentOperationalState.PENDING);
	}

}
