package com.lms.common.money;

/**
 * The platform-wide implicit currency, used everywhere a monetary amount has
 * no other currency source. Neither {@code course.price} (V11) nor {@code
 * course_billing_configuration.currency}/{@code course_billing_period.currency}
 * (V38/V39, for {@code MONTHLY}/{@code SESSION}/{@code CUSTOM} pricing) apply
 * here - this constant is specifically for the {@code FREE}/{@code ONE_TIME}
 * checkout path, which has no currency column anywhere to read from (V19's
 * own header comment on {@code student_order}/{@code payment.currency}: "a
 * single implicit currency is assumed platform-wide/per-tenant at MVP").
 *
 * <p>Kept in {@code com.lms.common} (the shared kernel, never dependent on
 * any business/domain module, per {@code .claude/rules/architecture.md}) so
 * both {@code paymentmanagement.order.service.OrderService} and {@code
 * coursemanagement.course.service.CourseLookupApiImpl} read the exact same
 * single source of truth for this value, rather than each module defining
 * its own "USD" literal that could silently drift out of sync.
 */
public final class PlatformCurrency {

	public static final String DEFAULT_CURRENCY = "USD";

	private PlatformCurrency() {
	}

}
