package com.lms.usermanagement.teacher.web.dto;

import java.util.List;
import java.util.UUID;

/**
 * TP-03: the signed-in Teacher's own profile (Teacher Portal -> My Profile).
 * Deliberately omits finance fields (institute commission), approval
 * metadata and public-profile visibility. {@code editableFields} is the
 * server's own allowlist; the frontend renders every other field read-only.
 */
public record TeacherSelfProfileResponse(UUID id, UUID userId, String name, String firstName, String lastName,
		String email, String contactNumber, String qualifications, String description, String profileImageUrl,
		String approvalStatus, List<String> editableFields) {
}
