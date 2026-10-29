package com.lms.usermanagement.teacher.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** DTO-only teacher view; never exposes password or password hash. */
public record TeacherResponse(UUID id, String name, String firstName, String lastName, String email,
		String contactNumber, BigDecimal instituteCommissionPercent, String qualifications, String description,
		UUID profileMediaFileId, String profileImageUrl, boolean publicProfileDisabled, long studentCount, String approvalStatus, String accountStatus, UUID approvedBy,
		Instant approvedAt, UUID userId) {

	/** Backwards-compatible constructor for existing test fixtures. */
	public TeacherResponse(UUID id, String name, String email, String approvalStatus, String accountStatus,
			UUID approvedBy, Instant approvedAt, UUID userId) {
		this(id, name, null, null, email, null, null, null, null, null, null, false, 0, approvalStatus, accountStatus,
				approvedBy, approvedAt, userId);
	}
}
