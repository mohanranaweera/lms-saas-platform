package com.lms.usermanagement.student.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Student profile edit request body (MVP-006). Shared by both the
 * staff-facing {@code PATCH /api/v1/students/{id}} endpoint and the
 * self-service {@code PATCH /api/v1/students/me} endpoint - both edit the
 * same {@code name} field, since the exact guardian/school field list is an
 * open, unresolved decision (plan §21 item 3) and is not modeled yet.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StudentUpdateRequest(@NotBlank @Size(max = 255) String name) {

}
