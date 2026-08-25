package com.lms.paymentmanagement.slip.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/payment-slips/{slipId}/approve} request body.
 * {@code overrideReason} is optional/nullable - absent or blank is only
 * legal when the target slip carries no active flags; {@code
 * SlipReviewService} independently enforces that requirement server-side
 * regardless of what this DTO validates, since presence of unresolved flags
 * (not client intent) is what triggers the requirement.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SlipApproveRequest(@Size(max = 1000) String overrideReason) {

	public SlipApproveRequest() {
		this(null);
	}

}
