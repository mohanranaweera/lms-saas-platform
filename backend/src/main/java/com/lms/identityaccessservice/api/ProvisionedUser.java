package com.lms.identityaccessservice.api;

import java.util.UUID;

/**
 * Narrow result of {@link UserProvisioningApi#provisionTenantUser}. Never the
 * {@code TenantUser} JPA entity itself - per this codebase's "don't expose
 * JPA entities across a module boundary" rule, callers receive only the
 * fields they need to persist their own local reference (the new row's id),
 * echo back in a response (the email), and relay out-of-band exactly once
 * ({@code temporaryPassword} - the raw, server-generated password; never
 * persisted or logged anywhere in plaintext beyond this one return value).
 */
public record ProvisionedUser(UUID userId, String email, String temporaryPassword) {

}
