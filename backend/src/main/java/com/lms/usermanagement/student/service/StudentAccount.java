package com.lms.usermanagement.student.service;

import java.util.UUID;

/**
 * Service-level result type composed from {@code user-management}'s own
 * {@code StudentProfile} row plus a read-only projection of the matching
 * {@code identity-access-service} {@code tenant_user} row (email, role,
 * status). {@code id} is the {@code StudentProfile}'s own id - the resource
 * id this module's {@code /api/v1/students} endpoints address - never the
 * underlying {@code tenant_user} id. {@code roleCode} is always {@code
 * "STUDENT"} in practice (this module only ever provisions {@code
 * STUDENT}-role accounts), kept as a field for response-shape consistency
 * with the {@code staff} package's precedent and because it is already
 * available for free from {@code TenantUserSummary.roleCode()}. {@code web}
 * maps this to the public {@code StudentResponse} DTO; this type itself
 * never crosses the controller boundary.
 */
public record StudentAccount(UUID id, String name, String email, String roleCode, String status) {

}
