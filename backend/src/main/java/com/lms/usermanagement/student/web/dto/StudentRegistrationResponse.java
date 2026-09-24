package com.lms.usermanagement.student.web.dto;

import java.util.UUID;

/**
 * Response for {@code POST /api/v1/students/register}. {@code
 * pendingApproval} mirrors whether the calling tenant's {@code
 * ConfigDomain.STUDENT/approval_required} property was {@code true} at
 * registration time - a {@code true} value means the account cannot log in
 * yet (tenant_user starts {@code SUSPENDED}) until staff activates it.
 */
public record StudentRegistrationResponse(UUID studentProfileId, String email, boolean pendingApproval) {

}
