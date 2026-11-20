package com.lms.paymentmanagement.discount.domain;

/**
 * The four Klass discount families (DISC-01, V92 {@code discount_rule.discount_type}).
 * {@link #tiePriority()} is the D6 tie-break order when two candidates save the
 * student the same amount: free-course > bundle > advance > code (lower wins).
 */
public enum DiscountType {

	ADVANCE_PAYMENT(2), BUNDLE_AMOUNT_OFF(1), BUNDLE_FREE_COURSE(0), DISCOUNT_CODE(3);

	private final int tiePriority;

	DiscountType(int tiePriority) {
		this.tiePriority = tiePriority;
	}

	public int tiePriority() {
		return tiePriority;
	}

}
