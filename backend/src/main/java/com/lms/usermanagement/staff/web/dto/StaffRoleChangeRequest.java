package com.lms.usermanagement.staff.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code PUT /api/v1/staff/{id}/role} body. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StaffRoleChangeRequest(@NotBlank @Size(max = 50) String roleCode) {

}
