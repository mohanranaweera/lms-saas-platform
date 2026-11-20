package com.lms.paymentmanagement.manualpayment.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lms.paymentmanagement.checkout.web.dto.CheckoutItemRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/v1/staff/students/{studentId}/manual-payments} (ADR-030,
 * plan §4). No tenant/student/price fields: the student is the URL's
 * student_profile id resolved server-side; {@code expectedReceivedAmount} is
 * only compared with the server's own figure (409 {@code AMOUNT_MISMATCH}),
 * never used as the amount. Field rules (method, dates, lengths, amounts) are
 * enforced by {@code ManualPaymentService} as 422s.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ManualPaymentRecordRequest(@Valid @Size(max = 100) List<CheckoutItemRequest> items,
		@Size(max = 20) List<UUID> postageCourseIds, @Size(max = 128) String quoteToken,
		BigDecimal staffAdjustmentAmount, String adjustmentReason, BigDecimal expectedReceivedAmount,
		String paymentMethod, LocalDate receivedOn, String referenceNumber, String notes, UUID idempotencyKey) {

}
