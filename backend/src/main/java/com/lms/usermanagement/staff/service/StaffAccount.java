package com.lms.usermanagement.staff.service;

import java.util.UUID;

/**
 * Service-level result type composed from {@code user-management}'s own
 * {@code StaffProfile} row plus a read-only projection of the matching
 * {@code identity-access-service} {@code tenant_user} row (email, role,
 * status). {@code id} is the {@code StaffProfile}'s own id - the resource id
 * this module's {@code /api/v1/staff} endpoints address - never the
 * underlying {@code tenant_user} id. {@code web} maps this to the public
 * {@code StaffResponse} DTO; this type itself never crosses the controller
 * boundary.
 */
public record StaffAccount(UUID id, String name, String email, String roleCode, String status) {

}
