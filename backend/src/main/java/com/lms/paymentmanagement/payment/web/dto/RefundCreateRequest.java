package com.lms.paymentmanagement.payment.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code POST /api/v1/payments/{id}/refunds} request body. {@code amount}
 * must be strictly positive (exactly {@code 0} is rejected by {@code
 * @DecimalMin("0.01")}); {@code reason} is required and non-blank per
 * V19's {@code ck_payment_refund_reason_not_blank} CHECK, enforced here too
 * so an empty/whitespace-only reason never reaches the service layer.
 *
 * <p>{@code idempotencyKey} is optional (nullable) - when a caller (e.g. a
 * retrying frontend after a network timeout) supplies the same key for the
 * same payment, {@code RefundService#processRefund} returns the original
 * refund instead of creating a second one (V20). A caller that omits it
 * behaves exactly as before - no regression.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RefundCreateRequest(

		@NotNull @DecimalMin(value = "0.01", inclusive = true) @Digits(integer = 10, fraction = 2) BigDecimal amount,

		@NotBlank @Size(max = 1000) String reason,

		UUID idempotencyKey) {

	public RefundCreateRequest(BigDecimal amount, String reason) {
		this(amount, reason, null);
	}

}
