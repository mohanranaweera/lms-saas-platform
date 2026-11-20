package com.lms.paymentmanagement.discount.service;

import com.lms.paymentmanagement.discount.domain.DiscountType;
import com.lms.paymentmanagement.discount.service.DiscountPricing.CartLine;
import java.time.YearMonth;

/**
 * D1 Advance Payment (Payment Type = Installments): a line is an advance line
 * iff it is a MONTHLY line of a required course whose explicit billing (year,
 * month) is strictly after the current tenant month. Only AVAILABLE offers ever
 * reach the engine (paid / pending items are rejected by the composer), so
 * paid and pending periods never count.
 */
public final class AdvancePaymentEvaluator implements DiscountEvaluator {

	@Override
	public DiscountType type() {
		return DiscountType.ADVANCE_PAYMENT;
	}

	@Override
	public boolean isEligibleLine(RuleSnapshot rule, CartLine line, YearMonth currentMonth) {
		return !line.reward() && "MONTHLY".equals(line.scope()) && rule.requiredCourseIds().contains(line.courseId())
				&& line.period() != null && line.period().isAfter(currentMonth);
	}

	@Override
	public String explanation(RuleSnapshot rule) {
		return DiscountPricingService.valueLabel(rule) + " off months paid in advance";
	}

}
