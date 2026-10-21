package com.lms.identityaccessservice.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.TooManyRequestsException;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.identityaccessservice.error.InvalidCredentialsException;
import com.lms.identityaccessservice.repository.TenantUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Change password while signed in (AUTH-PWD-01 / ADR-020). Requires the
 * current password; five wrong attempts in 15 minutes lock the action for the
 * rest of that window. Success clears {@code must_change_password}, supersedes
 * open tokens, revokes every other session (the current one stays) and
 * returns a fresh access token.
 */
@Service
public class PasswordChangeService {

	static final int MAX_FAILURES = 5;

	static final Duration LOCK_WINDOW = Duration.ofMinutes(15);

	private final TenantUserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final PasswordPolicy passwordPolicy;

	private final PasswordTokenService passwordTokenService;

	private final RefreshTokenService refreshTokenService;

	private final TokenService tokenService;

	private final AuthRateLimiter rateLimiter;

	private final AuditLogApi auditLogApi;

	public PasswordChangeService(TenantUserRepository users, PasswordEncoder passwordEncoder,
			PasswordPolicy passwordPolicy, PasswordTokenService passwordTokenService,
			RefreshTokenService refreshTokenService, TokenService tokenService, AuthRateLimiter rateLimiter,
			AuditLogApi auditLogApi) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.passwordPolicy = passwordPolicy;
		this.passwordTokenService = passwordTokenService;
		this.refreshTokenService = refreshTokenService;
		this.tokenService = tokenService;
		this.rateLimiter = rateLimiter;
		this.auditLogApi = auditLogApi;
	}

	/** A fresh access token for the current session. */
	public record ChangedPassword(String accessToken, Duration expiresIn) {

	}

	@Transactional
	public ChangedPassword change(String currentPassword, String newPassword) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (principal == null || principal.tenantId() == null) {
			throw new InvalidCredentialsException();
		}
		String lockKey = "pwchange:fail:" + principal.tenantId() + ":" + principal.userId();
		if (rateLimiter.isAtLimit(lockKey, MAX_FAILURES)) {
			throw new TooManyRequestsException("Too many attempts. Try again in 15 minutes.");
		}
		TenantUser user = users.findById(principal.userId()).orElseThrow(InvalidCredentialsException::new);
		if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
			rateLimiter.tryAcquire(lockKey, MAX_FAILURES, LOCK_WINDOW);
			throw new FieldValidationException("Invalid password",
					List.of(new FieldError("currentPassword", "is incorrect")));
		}
		passwordPolicy.check(newPassword, user.getEmail(), user.getPasswordHash());
		rateLimiter.reset(lockKey);

		Instant now = Instant.now();
		user.setOwnPassword(passwordEncoder.encode(newPassword), now);
		users.save(user);
		passwordTokenService.invalidateAllOpen(user.getId());
		refreshTokenService.revokeAllTenantSessions(user.getId(), principal.sessionId());
		auditLogApi.record(new AuditLogEntry(user.getId(), "auth.password_changed", "tenant_user", user.getId(), null,
				Map.of()));
		String accessToken = tokenService.issueTenantAccessToken(user.getId(), principal.tenantId(), user.getRole(),
				principal.sessionId());
		return new ChangedPassword(accessToken, tokenService.accessTokenTtl());
	}

}
