package com.lms.paymentmanagement.discount.service;

import com.lms.paymentmanagement.discount.domain.DiscountType;
import com.lms.paymentmanagement.discount.service.DiscountPricing.CartLine;
import java.time.YearMonth;

/**
 * Course Bundle - Amount Off: every non-reward line of a required course counts
 * and may be discounted. Selection is counted in DISTINCT courses (two months of
 * one course are one course).
 */
public final class BundleAmountOffEvaluator implements DiscountEvaluator {

	@Override
	public DiscountType type() {
		return DiscountType.BUNDLE_AMOUNT_OFF;
	}

	@Override
	public boolean isEligibleLine(RuleSnapshot rule, CartLine line, YearMonth currentMonth) {
		return !line.reward() && rule.requiredCourseIds().contains(line.courseId());
	}

	@Override
	public String explanation(RuleSnapshot rule) {
		return DiscountPricingService.valueLabel(rule) + " off for buying these courses together";
	}

}
