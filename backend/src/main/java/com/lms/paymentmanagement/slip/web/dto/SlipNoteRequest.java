package com.lms.paymentmanagement.slip.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** PAY-HUB-01: {@code POST /api/v1/payment-slips/{slipId}/notes}. The author always comes from the session. */
public record SlipNoteRequest(@NotBlank @Size(max = 1000) String body) {

}
