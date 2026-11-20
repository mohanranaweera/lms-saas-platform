package com.lms.paymentmanagement.discount.service;

import com.lms.paymentmanagement.discount.domain.DiscountType;
import com.lms.paymentmanagement.discount.service.DiscountPricing.CartLine;
import java.time.YearMonth;

/**
 * Type-specific part of a value discount (advance, bundle amount-off, code):
 * which non-reward lines of the order count for the rule's course selection
 * mode AND may receive its discount. Qualification (selection mode, minimum
 * purchase), amount and allocation are shared in {@link DiscountPricingService}.
 */
public interface DiscountEvaluator {

	DiscountType type();

	boolean isEligibleLine(RuleSnapshot rule, CartLine line, YearMonth currentMonth);

	/** Student-facing one-line description of the applied discount. */
	String explanation(RuleSnapshot rule);

}
