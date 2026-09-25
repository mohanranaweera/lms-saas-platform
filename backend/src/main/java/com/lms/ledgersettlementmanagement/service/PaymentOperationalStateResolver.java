package com.lms.ledgersettlementmanagement.service;

import com.lms.ledgersettlementmanagement.api.PaymentOperationalState;
import java.math.BigDecimal;

/**
 * The pure function at the heart of Wave 6 §3.2's {@code
 * PaymentOperationalState} projection - takes only already-resolved,
 * primitive facts about ONE order (never a repository/entity/api call
 * itself) and returns the single {@link PaymentOperationalState} that
 * applies. Kept deliberately side-effect-free and dependency-free so it is
 * exhaustively unit-testable without Spring/Mockito - see {@code
 * PaymentOperationalStateResolverTest} for one test per transition case in
 * plan §8.
 *
 * <h2>Precedence (most specific/most "in-progress" wins)</h2>
 * <ol>
 * <li>A {@code PAYMENT_CONFIRMED} ledger total {@code > 0} always wins over
 * every other signal (a payment cannot both be confirmed and still
 * "pending" - {@link com.lms.paymentmanagement.payment.domain.Payment}'s own
 * state machine guarantees at most one CONFIRMED payment per order) -
 * resolves to {@link PaymentOperationalState#REFUNDED} if the refunded
 * total has caught up to (or exceeds) the confirmed total, else {@link
 * PaymentOperationalState#PAID} (this is also where a *partial* refund
 * resolves - it stays {@code PAID}, per plan §8's explicit boundary test).
 * <li>Otherwise (no confirmed ledger entry has ever been written for this
 * order), an open manual slip ({@code SUBMITTED}/{@code UNDER_REVIEW}) beats
 * an open gateway payment attempt beats a purely-terminal-rejected history
 * beats "nothing has ever been attempted" - {@link
 * PaymentOperationalState#UNDER_REVIEW} &gt; {@link
 * PaymentOperationalState#PENDING} &gt; {@link
 * PaymentOperationalState#REJECTED} &gt; {@link
 * PaymentOperationalState#UNPAID}. In practice an order never has BOTH an
 * open slip and a pending gateway payment at once (the two confirmation
 * paths are mutually exclusive per order in this codebase), but this
 * ordering is deterministic and side-effect-free either way, so no upstream
 * caller needs to enforce that exclusivity itself.
 * </ol>
 */
public final class PaymentOperationalStateResolver {

	private PaymentOperationalStateResolver() {
	}

	/**
	 * @param confirmedTotal the sum of every {@code PAYMENT_CONFIRMED}
	 * ledger entry amount for this order ({@code null}/zero if none).
	 * @param refundedTotal the sum of every {@code REFUND} ledger entry
	 * amount for this order, as a POSITIVE magnitude ({@code null}/zero if
	 * none) - callers must negate {@code ledger_entry}'s own
	 * negative-amount sign convention before calling this method.
	 * @param hasPendingPayment {@code true} if any payment attempt for this
	 * order is currently {@code PENDING}.
	 * @param hasRejectedPayment {@code true} if any payment attempt for
	 * this order was {@code REJECTED} (regardless of whether a later
	 * attempt succeeded - the confirmed-total check above already takes
	 * precedence in that case).
	 * @param hasOpenSlip {@code true} if any manual payment slip for this
	 * order is currently {@code SUBMITTED} or {@code UNDER_REVIEW}.
	 * @param hasRejectedSlip {@code true} if any manual payment slip for
	 * this order was {@code REJECTED}.
	 */
	public static PaymentOperationalState resolve(BigDecimal confirmedTotal, BigDecimal refundedTotal,
			boolean hasPendingPayment, boolean hasRejectedPayment, boolean hasOpenSlip, boolean hasRejectedSlip) {
		// Presence, NOT magnitude: a genuinely CONFIRMED FREE-course payment
		// (ADR-015/V42) carries amount == 0.00, so `confirmedTotal.signum() >
		// 0` would WRONGLY treat it as "no confirmed payment" and misclassify
		// a real, active FREE enrollment as UNPAID. `confirmedTotal` is only
		// ever non-null here because at least one PAYMENT_CONFIRMED ledger
		// entry was found for this order (see the caller's aggregation) -
		// that fact alone, not its dollar amount, is what "has a confirmed
		// payment" means.
		boolean hasConfirmedPayment = confirmedTotal != null;
		if (hasConfirmedPayment) {
			BigDecimal refunded = (refundedTotal != null) ? refundedTotal : BigDecimal.ZERO;
			// refunded.signum() > 0 is required in addition to the
			// >= comparison - without it, a $0-confirmed FREE order with NO
			// refund at all (refunded == confirmedTotal == 0) would
			// incorrectly satisfy "refunded >= confirmed" and misclassify as
			// REFUNDED.
			if (refunded.signum() > 0 && refunded.compareTo(confirmedTotal) >= 0) {
				return PaymentOperationalState.REFUNDED;
			}
			return PaymentOperationalState.PAID;
		}
		if (hasOpenSlip) {
			return PaymentOperationalState.UNDER_REVIEW;
		}
		if (hasPendingPayment) {
			return PaymentOperationalState.PENDING;
		}
		if (hasRejectedPayment || hasRejectedSlip) {
			return PaymentOperationalState.REJECTED;
		}
		return PaymentOperationalState.UNPAID;
	}

}
