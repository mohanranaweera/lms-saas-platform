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

	/** One priced line; {@code courseId}/{@code courseTitle} = the course the line belongs to. */
	public record QuoteLine(int lineNo, String scope, String label, Integer year, Integer month, UUID moduleId,
			UUID lessonId, BigDecimal unitAmount, BigDecimal facilityFee, BigDecimal lineTotal, UUID courseId,
			String courseTitle) {

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
			String quoteToken, Methods methods, BankDetails bankDetails) {

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
			@JsonIgnore boolean idempotentReplay) {

	}

	/** The service-level composer request: items (+ default course) and the courses with postage selected. */
	public record CheckoutCart(UUID defaultCourseId, List<ItemSelection> items, List<UUID> postageCourseIds) {

		public CheckoutCart {
			items = items == null ? List.of() : items;
			postageCourseIds = postageCourseIds == null ? List.of() : postageCourseIds;
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
