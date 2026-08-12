package com.lms.usermanagement.student.web.dto;

import java.util.UUID;

/**
 * Public response shape for {@code /api/v1/students} endpoints. Never the
 * {@code StudentProfile} JPA entity - composed by {@code StudentController}
 * from {@code StudentService}'s {@code StudentAccount} result.
 */
public record StudentResponse(UUID id, String name, String email, String roleCode, String status) {

}
