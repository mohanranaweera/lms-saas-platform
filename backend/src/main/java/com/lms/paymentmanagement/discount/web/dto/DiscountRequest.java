package com.lms.paymentmanagement.discount.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lms.paymentmanagement.discount.service.DiscountAdminViews.DiscountInput;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/v1/admin/discounts} and {@code PUT /api/v1/admin/discounts/{id}}
 * body (plan §5). No tenant field - the tenant is always the caller's. All
 * validation is server-side in {@code DiscountAdminService} (422 {@code
 * DISCOUNT_VALIDATION} with field errors).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DiscountRequest(String name, String description, String discountType, List<UUID> courseIds,
		String selectionMode, Integer minCount, String paymentType, String valueType, BigDecimal value,
		List<UUID> rewardCourseIds, Integer freeMonths, String code, LocalDate validFrom, LocalDate validTo,
		Integer maxUses, BigDecimal minPurchaseAmount, Boolean active) {

	public DiscountInput toInput() {
		return new DiscountInput(name, description, discountType, courseIds, selectionMode, minCount, paymentType,
				valueType, value, rewardCourseIds, freeMonths, code, validFrom, validTo, maxUses, minPurchaseAmount,
				active);
	}

}
