package com.lms.usermanagement.teacher.service;

import java.time.Instant;
import java.util.UUID;

/**
 * Service-level result type composed from {@code user-management}'s own
 * {@code TeacherProfile} row plus a read-only projection of the matching
 * {@code identity-access-service} {@code tenant_user} row (email, status).
 * {@code id} is the {@code TeacherProfile}'s own id - the resource id this
 * module's {@code /api/v1/teachers} endpoints address - never the underlying
 * {@code tenant_user} id. {@code approvalStatus} is {@code
 * TeacherProfile.approvalStatus} (why the account is/isn't active);
 * {@code accountStatus} is {@code tenant_user.status} (the login-gate
 * mechanism, {@code "active"}/{@code "suspended"}) - two different concerns,
 * kept distinct per the approved MVP-007 plan §7. {@code web} maps this to
 * the public {@code TeacherResponse} DTO; this type itself never crosses the
 * controller boundary, and never carries a password/hash field.
 */
public record TeacherAccount(UUID id, String name, String email, String approvalStatus, String accountStatus,
		UUID approvedBy, Instant approvedAt) {

}
