package com.lms.enrollmentmanagement.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/enrollments/{id}/revoke} (Wave 3,
 * Student actions). {@code reason} is mandatory - a revoke with no recorded
 * reason is not a valid revoke, mirroring {@code payment_refund.reason}'s
 * NOT-blank precedent.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RevokeEnrollmentRequest(@NotBlank @Size(max = 1000) String reason) {

}
