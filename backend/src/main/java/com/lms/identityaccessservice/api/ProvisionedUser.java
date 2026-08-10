package com.lms.identityaccessservice.api;

import java.util.UUID;

/**
 * Narrow result of {@link UserProvisioningApi#provisionTenantUser}. Never the
 * {@code TenantUser} JPA entity itself - per this codebase's "don't expose
 * JPA entities across a module boundary" rule, callers receive only the two
 * fields they need to persist their own local reference (the new row's id)
 * and echo back in a response (the email).
 */
public record ProvisionedUser(UUID userId, String email) {

}
