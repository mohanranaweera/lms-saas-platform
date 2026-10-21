package com.lms.paymentmanagement.discount.service;

import com.lms.paymentmanagement.discount.domain.DiscountType;
import com.lms.paymentmanagement.discount.domain.DiscountValueType;
import com.lms.paymentmanagement.discount.domain.SelectionMode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The pricing-relevant terms of a rule's CURRENT version, as consumed by
 * {@link DiscountPricingService}. Plain data - never a JPA entity.
 *
 * @param requiredCourseIds the rule's qualifying courses, in a stable order.
 * @param rewardCourseIds BUNDLE_FREE_COURSE only: the free courses (else empty).
 */
public record RuleSnapshot(UUID ruleId, UUID versionId, DiscountType type, String name, String code,
		SelectionMode selectionMode, Integer minCount, DiscountValueType valueType, BigDecimal value, Integer freeMonths,
		LocalDate validFrom, LocalDate validTo, Integer maxUses, BigDecimal minPurchaseAmount, String currency,
		List<UUID> requiredCourseIds, List<UUID> rewardCourseIds) {

	public RuleSnapshot {
		requiredCourseIds = requiredCourseIds == null ? List.of() : List.copyOf(requiredCourseIds);
		rewardCourseIds = rewardCourseIds == null ? List.of() : List.copyOf(rewardCourseIds);
	}

	/**
	 * Owner decision 2026-10-21 (cap scope): one counting use per student
	 * applies only to DISCOUNT_CODE rules and rules with Maximum Uses set;
	 * uncapped automatic rules apply on every order.
	 */
	public boolean oneUsePerStudent() {
		return oneUsePerStudent(type, maxUses);
	}

	public static boolean oneUsePerStudent(DiscountType type, Integer maxUses) {
		return type == DiscountType.DISCOUNT_CODE || maxUses != null;
	}

}
