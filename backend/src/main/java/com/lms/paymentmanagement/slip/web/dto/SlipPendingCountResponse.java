package com.lms.paymentmanagement.slip.web.dto;

/** PAY-HUB-01: {@code GET /api/v1/payment-slips/review-queue/count} - SUBMITTED + UNDER_REVIEW slips in the caller's tenant. */
public record SlipPendingCountResponse(long pending) {

}
