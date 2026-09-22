package com.lms.coursemanagement.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The resolved checkout amount for a course, per its current {@code
 * pricing_model} (V37) - returned by {@link CourseLookupApi
 * #getResolvedCheckoutAmount(UUID)}, the single place {@code
 * payment-management}'s {@code OrderService} resolves what a student owes at
 * checkout time, replacing the old {@code ONE_TIME}-only {@link
 * CourseLookupApi#getCurrentPrice(UUID)} read.
 *
 * @param amount the resolved amount, or {@code null} only when {@code
 * requiresManualQuote} is {@code true} (a {@code CUSTOM}-priced course with
 * no system-resolvable amount - the caller must supply one, from an
 * authorized staff actor only, never a student).
 * @param currency the ISO-4217 currency code the amount (once known) is
 * denominated in.
 * @param billingPeriodId the {@code course_billing_period} row this amount
 * was resolved from, for {@code MONTHLY}/{@code SESSION} pricing only;
 * {@code null} for every other pricing model (there is no billing-period
 * traceability for a flat {@code ONE_TIME}/{@code FREE}/{@code CUSTOM}
 * checkout).
 * @param requiresManualQuote {@code true} only for {@code CUSTOM} pricing -
 * signals the caller that {@code amount} is deliberately absent and must be
 * supplied by an authorized staff actor.
 * @param freePricing {@code true} only when the course's own {@code
 * pricing_model} is genuinely {@code FREE} (added per ADR-015, Phase E
 * review fix) - deliberately a narrow boolean, not the raw {@code
 * course.domain.CoursePricingModel} enum, so this cross-module contract
 * never leaks a {@code course-management}-internal {@code domain}-package
 * type to another module (per {@code .claude/rules/architecture.md}: a
 * module may depend only on another module's {@code api} package, never
 * import another domain's {@code domain} classes). {@code
 * payment-management}'s {@code OrderService} MUST branch its FREE-checkout
 * auto-activation on THIS field, never on {@code amount} alone resolving to
 * {@code $0}: a misconfigured {@code ONE_TIME} price or {@code MONTHLY}/
 * {@code SESSION} billing period can also resolve to {@code $0} without the
 * course genuinely being FREE-priced, and that case must be rejected as a
 * misconfiguration, not silently auto-activated.
 */
public record CheckoutAmount(BigDecimal amount, String currency, UUID billingPeriodId, boolean requiresManualQuote,
		boolean freePricing) {

}
