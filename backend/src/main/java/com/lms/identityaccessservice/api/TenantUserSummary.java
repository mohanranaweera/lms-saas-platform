package com.lms.identityaccessservice.api;

import java.util.UUID;

/**
 * Narrow, read-only projection of a {@code tenant_user} row, returned by
 * {@link UserProvisioningApi#findTenantUserSummaries}. Added alongside
 * {@link ProvisionedUser} because a caller composing a list/detail view of an
 * account it provisioned (e.g. {@code user-management}'s Staff Management
 * listing/detail endpoints) needs the credential-owned fields (email, role,
 * status) that are deliberately NOT duplicated onto the caller's own
 * profile table - never the {@code TenantUser} JPA entity itself.
 */
public record TenantUserSummary(UUID userId, String email, String roleCode, String status) {

}
