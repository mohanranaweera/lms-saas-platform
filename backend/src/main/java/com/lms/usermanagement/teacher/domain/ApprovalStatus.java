package com.lms.usermanagement.teacher.domain;

/**
 * The review outcome of a {@link TeacherProfile}, matching {@code
 * teacher_profile.approval_status}'s {@code CHECK (approval_status IN
 * ('PENDING', 'APPROVED', 'REJECTED'))} constraint (V18) exactly - the enum
 * constant names are written as-is via {@code @Enumerated(EnumType.STRING)}.
 * Transitions are one-directional and terminal: {@code PENDING -> APPROVED}
 * or {@code PENDING -> REJECTED} only, enforced by {@link
 * TeacherProfile#approve} / {@link TeacherProfile#reject}, never {@code
 * APPROVED -> PENDING} or {@code REJECTED -> APPROVED}.
 *
 * <p>{@code SUSPENDED} (Wave 3, master instruction §11) is a second,
 * independent transition pair layered on top of the original approval
 * workflow: only {@code APPROVED -> SUSPENDED} and {@code SUSPENDED ->
 * APPROVED} are legal, enforced by {@link TeacherProfile#suspend}/{@link
 * TeacherProfile#reactivate} - never reachable from {@code PENDING}/{@code
 * REJECTED}, and never itself a substitute for those two terminal outcomes.
 */
public enum ApprovalStatus {

	PENDING, APPROVED, REJECTED, SUSPENDED

}
