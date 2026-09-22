package com.lms.coursemanagement.api;

import com.lms.common.error.ConflictException;

/**
 * Thrown by {@link CourseLookupApi#getResolvedCheckoutAmount(java.util.UUID)}
 * when a course's {@code pricing_model} (V37) is {@code MONTHLY}/{@code
 * SESSION} but no {@code course_billing_configuration} (V38) or no OPEN
 * {@code course_billing_period} (V39) exists for it, or when it is {@code
 * CUSTOM} but no {@code course_billing_configuration} exists at all. This is
 * a genuine misconfiguration - a course advertised as recurring/session/
 * custom-priced with nothing actually configured to bill against - never
 * silently treated as a {@code $0} checkout. Extends {@link ConflictException}
 * ({@code 409}) rather than a raw {@code 500}: the client-safe message is
 * "this course isn't ready to be purchased yet," a legitimate, expected state
 * a newly-created course can be in before its owning Teacher/staff finishes
 * configuring billing.
 */
public class CourseBillingNotConfiguredException extends ConflictException {

	public CourseBillingNotConfiguredException(String message) {
		super(message);
	}

}
