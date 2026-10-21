package com.lms.identityaccessservice.api;

import java.util.UUID;

/**
 * AUTH-PWD-01 / ADR-020: a user should receive an account-setup or
 * password-reset email. Carries no token - notification-management asks
 * {@link PasswordTokenIssuingApi} for a fresh one at send time, so the raw
 * token is never stored (MVP-018's outbox rule).
 */
public record PasswordEmailRequestedEvent(UUID tenantId, UUID userId, PasswordTokenPurpose purpose) {

}
