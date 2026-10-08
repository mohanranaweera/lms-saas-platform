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

	/** A bounded, non-reversible attribution value suitable for video overlays. */
	public String maskedEmail() {
		if (email == null || email.isBlank()) {
			return null;
		}
		int at = email.indexOf('@');
		if (at <= 0 || at == email.length() - 1) {
			return "***";
		}
		String local = email.substring(0, at);
		String domain = email.substring(at + 1);
		String visible = local.substring(0, 1);
		return visible + "***@" + domain;
	}

}
