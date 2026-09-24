package com.lms.usermanagement.student.domain;

/**
 * Mirrors {@code student_profile.registration_status}'s {@code CHECK
 * (registration_status IN ('ADMIN_CREATED', 'SELF_REGISTERED'))} constraint
 * (V45) exactly - Wave 3 (Student registration expansion). Distinguishes a
 * staff/admin-created account from one created via the public {@code POST
 * /api/v1/students/register} endpoint, so the activate/deactivate UI (and
 * any future reporting) can tell a pending-approval self-registered student
 * apart from a staff-deactivated one.
 */
public enum StudentRegistrationStatus {

	ADMIN_CREATED, SELF_REGISTERED

}
