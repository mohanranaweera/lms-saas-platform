package com.lms.paymentmanagement.discount.service;

import com.lms.paymentmanagement.discount.domain.DiscountType;
import com.lms.paymentmanagement.discount.service.DiscountPricing.CartLine;
import java.time.YearMonth;

/**
 * Discount Code: only evaluated when the entered (normalized) code matches the
 * rule; then like a bundle - every non-reward line of a required course counts
 * and may be discounted.
 */
public final class DiscountCodeEvaluator implements DiscountEvaluator {

	@Override
	public DiscountType type() {
		return DiscountType.DISCOUNT_CODE;
	}

	@Override
	public boolean isEligibleLine(RuleSnapshot rule, CartLine line, YearMonth currentMonth) {
		return !line.reward() && rule.requiredCourseIds().contains(line.courseId());
	}

	@Override
	public String explanation(RuleSnapshot rule) {
		return "Code " + rule.code() + ": " + DiscountPricingService.valueLabel(rule) + " off";
	}

}
