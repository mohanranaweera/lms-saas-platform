package com.lms.usermanagement.student.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * {@code PUT /api/v1/students/me/profile} and {@code
 * /api/v1/students/{id}/profile-fields} request body (STU-PROF-01): field key
 * to value; keys left out stay unchanged, a null value clears the field.
 * Keys and values are validated by {@code StudentProfileFieldsService}
 * against the tenant configuration.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StudentProfileFieldsUpdateRequest(@NotNull @Size(max = 100) Map<String, Object> values) {

}
