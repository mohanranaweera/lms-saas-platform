package com.lms.coursemanagement.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * DISC-01: one course of the caller's tenant with its current display price,
 * returned by {@link CourseLookupApi#findCoursePriceOptions()} for the tenant
 * admin's discount-rule course pickers and server-side course validation.
 *
 * @param pricingModel {@code FREE|ONE_TIME|MONTHLY|SESSION|CUSTOM|DAILY|MODULE|INSTALLMENT}.
 * @param status {@code DRAFT|PRIVATE|PUBLIC}.
 * @param price ONE_TIME: the course price; MONTHLY: the current open billing
 * period amount; otherwise (or when not configured) {@code null}.
 * @param currency the currency of {@code price} ({@code null} when {@code price} is).
 */
public record CoursePriceOption(UUID courseId, String name, String pricingModel, String status, boolean archived,
		BigDecimal price, String currency) {

	public boolean monthly() {
		return "MONTHLY".equals(pricingModel);
	}

}
