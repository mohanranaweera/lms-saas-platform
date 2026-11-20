package com.lms.paymentmanagement.checkout.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lms.paymentmanagement.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * KLASS-T4c response shapes of the payment composer (plan §4.4) - plain
 * records, never JPA entities. Money is {@code BigDecimal} scale 2 in the
 * order/offer currency.
 */
public final class CheckoutViews {

	private CheckoutViews() {
	}

	/**
	 * {@code GET /api/v1/checkout/courses}. {@code tags} (R2-D6, additive) lets
	 * the composer filter its course selector by tag; never null.
	 */
	public record CheckoutCourse(UUID courseId, String title, List<CheckoutCourseTag> tags) {

		public CheckoutCourse {
			tags = tags == null ? List.of() : List.copyOf(tags);
		}

	}

	/** A course tag on a {@link CheckoutCourse}: id + display name only. */
	public record CheckoutCourseTag(UUID id, String name) {

	}

	/**
	 * {@code GET /api/v1/checkout/offers}.
	 * @param years billing years present (MONTHLY only; empty for other scopes).
	 */
	public record CheckoutOffers(String scope, UUID courseId, String currency, boolean supported, List<Integer> years,
			List<OfferItem> items) {

	}

	/** @param state {@code AVAILABLE|PAID|PENDING}. */
	public record OfferItem(String scope, Integer year, Integer month, UUID moduleId, UUID lessonId, String label,
			BigDecimal amount, String state) {

	}

	/**
	 * One priced line; {@code courseId}/{@code courseTitle} = the course the line
	 * belongs to. DISC-01 (additive): {@code discountAmount} (never more than
	 * {@code unitAmount}; facility fees are never discounted), {@code reward}
	 * (a free-course reward line: {@code discountAmount = unitAmount}) and
	 * {@code discountName} (the applied discount's name, else {@code null}).
	 * {@code lineTotal = unitAmount + facilityFee - discountAmount}.
	 */
	public record QuoteLine(int lineNo, String scope, String label, Integer year, Integer month, UUID moduleId,
			UUID lessonId, BigDecimal unitAmount, BigDecimal facilityFee, BigDecimal lineTotal, UUID courseId,
			String courseTitle, BigDecimal discountAmount, boolean reward, String discountName) {

		/** Pre-DISC-01 shape (no discount). */
		public QuoteLine(int lineNo, String scope, String label, Integer year, Integer month, UUID moduleId,
				UUID lessonId, BigDecimal unitAmount, BigDecimal facilityFee, BigDecimal lineTotal, UUID courseId,
				String courseTitle) {
			this(lineNo, scope, label, year, month, moduleId, lessonId, unitAmount, facilityFee, lineTotal, courseId,
					courseTitle, BigDecimal.ZERO.setScale(2), false, null);
		}

	}

	/**
	 * DISC-01: one discount applied to a quote / order - only the applied
	 * rule's own student-visible terms, never limits or usage.
	 * @param type {@code ADVANCE_PAYMENT|BUNDLE_AMOUNT_OFF|BUNDLE_FREE_COURSE|DISCOUNT_CODE}.
	 * @param code the code (DISCOUNT_CODE only).
	 * @param lineNos the lines carrying this discount.
	 * @param rewardCourseId BUNDLE_FREE_COURSE: the (first) free course, else {@code null};
	 * {@code rewardCourseIds} lists all of them.
	 */
	public record AppliedDiscountView(UUID ruleId, UUID ruleVersionId, String type, String name, String code,
			BigDecimal amount, List<Integer> lineNos, UUID rewardCourseId, List<UUID> rewardCourseIds,
			String explanation) {

	}

	/**
	 * DISC-01: status of the code the student entered.
	 * @param status {@code APPLIED|INVALID|NOT_ELIGIBLE|USAGE_LIMIT_REACHED|NOT_APPLIED_BETTER_DISCOUNT} - a
	 * disabled, expired or not-yet-valid code is {@code INVALID} (DISC-01 hardening, 2026-10-21).
	 */
	public record CodeResult(String code, String status, String message) {

	}

	/** One month of a free-course offer. */
	public record RewardMonth(int year, int month, String label) {

	}

	/**
	 * DISC-01: free months the student qualifies for but has not accepted yet
	 * (re-quote with the rule id in {@code acceptedRewardRuleIds} to add them).
	 * @param originalValue the months' normal course value (becomes 0).
	 * @param facilityFee the facility fee those lines would add under the
	 * tenant's existing fee rules.
	 */
	public record RewardOffer(UUID ruleId, String name, UUID courseId, String courseTitle, List<RewardMonth> months,
			BigDecimal originalValue, BigDecimal facilityFee, String explanation) {

	}

	/**
	 * Per-course charges of a quote / order (V90 {@code
	 * student_order_course_charge}).
	 * @param subtotal sum of the course's line unit amounts.
	 * @param facilityFee facility fee actually charged to this course.
	 * @param effectiveFacilityFee the course's per-basis-unit fee (override or
	 * tenant default; 0 for NONE; tenant default for PER_ORDER).
	 * @param facilityFeeSource {@code COURSE_OVERRIDE|TENANT_DEFAULT|NOT_APPLICABLE}.
	 * @param postageFee the course's configured postage.
	 * @param postageCharge {@code postageFee} when selected, else 0.
	 */
	public record CourseCharge(UUID courseId, String courseTitle, String currency, BigDecimal subtotal,
			BigDecimal facilityFee, BigDecimal effectiveFacilityFee, String facilityFeeSource, BigDecimal postageFee,
			boolean postageSelected, BigDecimal postageCharge) {

	}

	/** Delivery address (the student's on-record billing address, or an order's snapshot of it). */
	public record DeliveryDetails(String recipientName, String addressLine, String city, String phone) {

	}

	public record Methods(boolean online, boolean slip) {

	}

	/** Tenant PAYMENT config (display only); {@code null} as a whole when nothing is configured. */
	public record BankDetails(String bankName, String branch, String accountHolder, String accountNumber) {

	}

	/**
	 * {@code POST /api/v1/checkout/quote}. {@code courseId} is the single
	 * course of the cart, {@code null} when it spans several. {@code feeBasis}
	 * is the tenant facility-fee mode. {@code delivery} = the on-record billing
	 * address ({@code null} when nothing is on record); {@code
	 * deliveryRequired} = postage is selected. {@code quoteToken} = SHA-256 of
	 * the canonical breakdown; {@code POST /orders} must echo it.
	 */
	public record CheckoutQuote(UUID courseId, String currency, List<QuoteLine> lines, List<CourseCharge> courses,
			BigDecimal subtotal, BigDecimal facilityFeeTotal, BigDecimal postageTotal, BigDecimal discountTotal,
			BigDecimal total, String feeBasis, String feeNote, boolean deliveryRequired, DeliveryDetails delivery,
			String quoteToken, Methods methods, BankDetails bankDetails, List<AppliedDiscountView> discounts,
			CodeResult codeResult, List<RewardOffer> rewardOffers, List<String> notices, String blockingIssue,
			String blockingMessage) {

		/**
		 * DISC-01 additions: {@code discounts} (applied), {@code codeResult}
		 * ({@code null} when no code was entered), {@code rewardOffers}, {@code
		 * notices}, {@code blockingIssue} ({@code ORDER_TOTAL_ZERO} or {@code
		 * null}) + {@code blockingMessage}. Lists are never null.
		 */
		public CheckoutQuote {
			discounts = discounts == null ? List.of() : List.copyOf(discounts);
			rewardOffers = rewardOffers == null ? List.of() : List.copyOf(rewardOffers);
			notices = notices == null ? List.of() : List.copyOf(notices);
		}

	}

	/**
	 * {@code POST /api/v1/checkout/orders}: the existing {@code OrderResponse}
	 * fields plus the line / per-course charge / postage / delivery snapshot.
	 * {@code courseId} is {@code null} for a multi-course order; {@code
	 * delivery} is {@code null} when no postage was selected.
	 */
	public record CheckoutOrder(UUID id, UUID studentId, UUID courseId, BigDecimal amount, String currency,
			UUID billingPeriodId, OrderStatus status, Instant createdAt, Instant updatedAt, List<QuoteLine> lines,
			List<CourseCharge> courses, BigDecimal subtotal, BigDecimal facilityFeeTotal, BigDecimal postageTotal,
			BigDecimal discountTotal, String feeBasis, DeliveryDetails delivery,
			@JsonIgnore boolean idempotentReplay, List<AppliedDiscountView> discounts) {

		/** DISC-01: {@code discounts} = the order's immutable applied-discount snapshot (never null). */
		public CheckoutOrder {
			discounts = discounts == null ? List.of() : List.copyOf(discounts);
		}

	}

	/** The service-level composer request: items (+ default course) and the courses with postage selected. */
	public record CheckoutCart(UUID defaultCourseId, List<ItemSelection> items, List<UUID> postageCourseIds,
			String discountCode, List<UUID> acceptedRewardRuleIds) {

		/**
		 * DISC-01: {@code discountCode} (raw, normalized server-side; {@code
		 * null} = none) and {@code acceptedRewardRuleIds} (free-course offers the
		 * student accepted; unknown / foreign ids are ignored with a notice).
		 */
		public CheckoutCart {
			items = items == null ? List.of() : items;
			postageCourseIds = postageCourseIds == null ? List.of() : postageCourseIds;
			acceptedRewardRuleIds = acceptedRewardRuleIds == null ? List.of()
					: acceptedRewardRuleIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
		}

		public CheckoutCart(UUID defaultCourseId, List<ItemSelection> items, List<UUID> postageCourseIds) {
			this(defaultCourseId, items, postageCourseIds, null, null);
		}

	}

	/** One requested item (client prices are never part of it). */
	public record ItemSelection(String scope, Integer year, Integer month, UUID moduleId, UUID lessonId,
			UUID courseId) {

		public ItemSelection(String scope, Integer year, Integer month, UUID moduleId, UUID lessonId) {
			this(scope, year, month, moduleId, lessonId, null);
		}

	}

}
