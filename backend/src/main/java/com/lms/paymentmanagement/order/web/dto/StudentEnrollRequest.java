package com.lms.paymentmanagement.order.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/students/{id}/enroll} (Wave 3, Student
 * actions - staff "enroll student in course"). Lives in {@code
 * payment-management} (the module that now owns this endpoint - see {@link
 * com.lms.paymentmanagement.order.web.StudentEnrollmentController}'s class
 * javadoc for the architecture review finding this move closes), not {@code
 * user-management} - same field shape as the endpoint's original {@code
 * user-management}-owned DTO, so the public API contract is unchanged.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StudentEnrollRequest(@NotNull UUID courseId, @NotBlank @Size(max = 1000) String reason) {

}
