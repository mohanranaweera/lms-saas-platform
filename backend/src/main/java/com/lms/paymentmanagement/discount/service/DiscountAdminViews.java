package com.lms.paymentmanagement.discount.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * DISC-01 admin shapes (plan §5) - plain records, never JPA entities. Only
 * served to tenant staff holding {@code DISCOUNTS} VIEW; students never see
 * these (they contain limits and usage counters).
 */
public final class DiscountAdminViews {

	private DiscountAdminViews() {
	}

	/**
	 * Create / edit input. Enum-like fields are strings so an unknown value is a
	 * 422 field error, not a deserialization error. Fields that do not apply to
	 * the chosen type (e.g. {@code freeMonths} on a code rule, {@code minCount}
	 * outside MIN_COUNT) are ignored and stored as null.
	 */
	public record DiscountInput(String name, String description, String discountType, List<UUID> courseIds,
			String selectionMode, Integer minCount, String paymentType, String valueType, BigDecimal value,
			List<UUID> rewardCourseIds, Integer freeMonths, String code, LocalDate validFrom, LocalDate validTo,
			Integer maxUses, BigDecimal minPurchaseAmount, Boolean active) {

	}

	public record CourseRef(UUID courseId, String name) {

	}

	/**
	 * One rule (current version).
	 * @param status {@code INACTIVE} (toggle off), {@code SCHEDULED} (before
	 * validFrom), {@code EXPIRED} (after validTo) or {@code ACTIVE} - tenant dates.
	 * @param valueLabel e.g. {@code "LKR 200.00 OFF"}, {@code "10% OFF"}, {@code "1 MONTH FREE"}.
	 * @param usedCount CONSUMED redemptions; {@code reservedCount} live RESERVED holds.
	 * @param code the normalized code (DISCOUNT_CODE only); {@code "******"} for a
	 * caller without DISCOUNTS CREATE_EDIT.
	 */
	public record DiscountRuleView(UUID id, String name, String description, String discountType, boolean active,
			String status, List<UUID> courseIds, List<CourseRef> courses, String selectionMode, Integer minCount,
			String paymentType, String valueType, BigDecimal value, String valueLabel, List<UUID> rewardCourseIds,
			List<CourseRef> rewardCourses, Integer freeMonths, String code, LocalDate validFrom, LocalDate validTo,
			Integer maxUses, BigDecimal minPurchaseAmount, String currency, int versionNo, long versionCount,
			long usedCount, long reservedCount, Instant createdAt, Instant updatedAt) {

	}

	public record DiscountCounts(long all, long active, long inactive) {

	}

	/** {@code GET /api/v1/admin/discounts}: the filtered items plus the three tab counts (archived excluded). */
	public record DiscountListView(List<DiscountRuleView> items, DiscountCounts counts) {

	}

	/**
	 * {@code GET /api/v1/admin/discounts/course-options}.
	 * @param priceLabel e.g. {@code "LKR 2500.00"}; {@code null} when the course has no price.
	 * @param monthly MONTHLY-priced (required for free / advance courses).
	 */
	public record CourseOptionView(UUID courseId, String name, String priceLabel, boolean monthly, String pricingModel,
			BigDecimal price, String currency) {

	}

}
