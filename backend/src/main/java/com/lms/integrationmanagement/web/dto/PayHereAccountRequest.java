package com.lms.integrationmanagement.web.dto;

import com.lms.integrationmanagement.domain.GatewayMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * PAY-GW-01: {@code merchantSecret} is optional on update (blank keeps the
 * stored secret) and required on first save - enforced in the service.
 */
public record PayHereAccountRequest(@NotBlank @Size(max = 64) String merchantId,
		@Size(max = 256) String merchantSecret, @NotNull GatewayMode mode) {

	@Override
	public String toString() {
		// Never render the secret (e.g. in validation/debug logs).
		return "PayHereAccountRequest[merchantId=" + merchantId + ", merchantSecret=***, mode=" + mode + "]";
	}

}
