package com.lms.usermanagement.student.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Self-service billing address update (PAY-GW-01 follow-up) - both fields required. */
public record StudentBillingAddressRequest(@NotBlank @Size(max = 255) String addressLine,
		@NotBlank @Size(max = 100) String city) {

}
