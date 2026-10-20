package com.lms.coursemanagement.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Course-level facts the participation/evaluator/composer modules need
 * (KLASS-T4b/T4c), returned by {@link CourseLookupApi#findCatalogInfo(UUID)}.
 * Enum-shaped fields are exposed as their stable names so this contract does
 * not leak course-management's {@code domain} types.
 *
 * @param pricingModel {@code FREE|ONE_TIME|MONTHLY|SESSION|CUSTOM|DAILY|MODULE|INSTALLMENT}.
 * @param status {@code DRAFT|PRIVATE|PUBLIC}.
 * @param accessDurationDays "Payment Valid Days"; {@code null} = no limit.
 * @param monthlyAmount the current open billing period amount for a MONTHLY
 * course, else {@code null} (also {@code null} if none is configured yet).
 * @param monthlyCurrency the currency of {@code monthlyAmount}, else {@code null}.
 * @param monthlyBillingPeriodId the open billing period id, else {@code null}.
 * @param currency the tenant default currency (for module/lesson prices).
 * @param facilityFeeOverride the course facility-fee override (V90); {@code
 * null} = inherit the tenant default, {@code 0} = no fee.
 * @param postageFee the configured course postage fee (V90), never {@code null}.
 */
public record CourseCatalogInfo(UUID courseId, String name, UUID teacherId, String pricingModel, String status,
		boolean archived, boolean registrationEnabled, Integer accessDurationDays, BigDecimal monthlyAmount,
		String monthlyCurrency, UUID monthlyBillingPeriodId, String currency, BigDecimal facilityFeeOverride,
		BigDecimal postageFee) {

	public CourseCatalogInfo {
		postageFee = postageFee == null ? BigDecimal.ZERO : postageFee;
	}

	/** Pre-V90 shape (no override, no postage). */
	public CourseCatalogInfo(UUID courseId, String name, UUID teacherId, String pricingModel, String status,
			boolean archived, boolean registrationEnabled, Integer accessDurationDays, BigDecimal monthlyAmount,
			String monthlyCurrency, UUID monthlyBillingPeriodId, String currency) {
		this(courseId, name, teacherId, pricingModel, status, archived, registrationEnabled, accessDurationDays,
				monthlyAmount, monthlyCurrency, monthlyBillingPeriodId, currency, null, BigDecimal.ZERO);
	}

	public boolean available() {
		return "PUBLIC".equals(status) && !archived;
	}

}
