package com.lms.usermanagement.student.web.dto;

import java.util.List;

/**
 * {@code GET/PUT /api/v1/students/me/profile} and {@code
 * /api/v1/students/{id}/profile-fields} response body (STU-PROF-01).
 */
public record StudentProfileFieldsResponse(List<StudentProfileFieldValueResponse> fields,
		List<String> missingRequiredFields) {

}
