package com.lms.paymentmanagement.slip.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /api/v1/payment-slips/{slipId}/reject} request body - {@code reason} is required, non-blank. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SlipRejectRequest(@NotBlank @Size(max = 1000) String reason) {

}
