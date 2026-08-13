package com.lms.usermanagement.teacher.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Public response shape for {@code /api/v1/teachers} endpoints. Never the
 * {@code TeacherProfile} JPA entity - composed by {@code TeacherController}
 * from {@code TeacherService}'s {@code TeacherAccount} result. Never exposes
 * a password/hash field - no such field exists anywhere in this chain.
 */
public record TeacherResponse(UUID id, String name, String email, String approvalStatus, String accountStatus,
		UUID approvedBy, Instant approvedAt) {

}
