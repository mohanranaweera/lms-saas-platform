package com.lms.usermanagement.teacher.domain;

/**
 * The review outcome of a {@link TeacherProfile}, matching {@code
 * teacher_profile.approval_status}'s {@code CHECK (approval_status IN
 * ('PENDING', 'APPROVED', 'REJECTED'))} constraint (V11) exactly - the enum
 * constant names are written as-is via {@code @Enumerated(EnumType.STRING)}.
 * Transitions are one-directional and terminal: {@code PENDING -> APPROVED}
 * or {@code PENDING -> REJECTED} only, enforced by {@link
 * TeacherProfile#approve} / {@link TeacherProfile#reject}, never {@code
 * APPROVED -> PENDING} or {@code REJECTED -> APPROVED}.
 */
public enum ApprovalStatus {

	PENDING, APPROVED, REJECTED

}
