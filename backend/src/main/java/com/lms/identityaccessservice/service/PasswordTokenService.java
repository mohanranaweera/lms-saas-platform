package com.lms.identityaccessservice.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.PasswordTokenIssuingApi;
import com.lms.identityaccessservice.api.PasswordTokenPurpose;
import com.lms.identityaccessservice.domain.AccountStatus;
import com.lms.identityaccessservice.domain.PasswordToken;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.identityaccessservice.error.TokenInvalidException;
import com.lms.identityaccessservice.repository.PasswordTokenRepository;
import com.lms.identityaccessservice.repository.TenantUserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time account-setup / password-reset tokens (AUTH-PWD-01 / ADR-020).
 *
 * <ul>
 * <li>Issue: 256 random bits, base64url; only the SHA-256 (hex) is stored.
 * Issuing supersedes the user's earlier unused tokens of that purpose.</li>
 * <li>Verify / complete: looked up in the request's tenant only - another
 * tenant's token is simply "invalid". Every failure is the same {@link
 * TokenInvalidException}.</li>
 * <li>Complete is single-use under a row lock, sets the password, supersedes
 * every other open token of the user and revokes all of the user's
 * sessions.</li>
 * </ul>
 */
@Service
public class PasswordTokenService implements PasswordTokenIssuingApi {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final PasswordTokenRepository tokens;

	private final TenantUserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final PasswordPolicy passwordPolicy;

	private final RefreshTokenService refreshTokenService;

	private final TenantContext tenantContext;

	private final AuditLogApi auditLogApi;

	private final AuthRateLimiter rateLimiter;

	static final int LINK_ATTEMPTS_PER_IP = 20;

	static final java.time.Duration LINK_ATTEMPT_WINDOW = java.time.Duration.ofMinutes(10);

	public PasswordTokenService(PasswordTokenRepository tokens, TenantUserRepository users,
			PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy, RefreshTokenService refreshTokenService,
			TenantContext tenantContext, AuditLogApi auditLogApi, AuthRateLimiter rateLimiter) {
		this.tokens = tokens;
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.passwordPolicy = passwordPolicy;
		this.refreshTokenService = refreshTokenService;
		this.tenantContext = tenantContext;
		this.auditLogApi = auditLogApi;
		this.rateLimiter = rateLimiter;
	}

	/** Per-IP limit on link verify/complete attempts (guessing is infeasible anyway; this caps noise). */
	public void guardLinkAttempt(String clientIp) {
		if (!rateLimiter.tryAcquire("link:ip:" + tenantContext.getTenantId() + ":" + clientIp, LINK_ATTEMPTS_PER_IP,
				LINK_ATTEMPT_WINDOW)) {
			throw new com.lms.common.error.TooManyRequestsException("Too many attempts. Try again in a few minutes.");
		}
	}

	/** What a valid link shows before the new password is entered. */
	public record TokenInfo(PasswordTokenPurpose purpose, String emailMasked, Instant expiresAt) {

	}

	@Override
	@Transactional
	public IssuedPasswordToken issueForEmail(UUID userId, PasswordTokenPurpose purpose) {
		TenantUser user = users.findById(userId).orElseThrow(() -> new IllegalStateException("Unknown user"));
		if (!appliesTo(purpose, user.getStatus())) {
			throw new IllegalStateException("The account is no longer in a state for " + purpose);
		}
		Instant now = Instant.now();
		tokens.findUnconsumed(userId, purpose).forEach(token -> token.invalidate(now));
		String raw = newRawToken();
		PasswordToken token = tokens.save(
				new PasswordToken(tenantContext.getTenantId(), userId, purpose, hash(raw), now, null));
		return new IssuedPasswordToken(raw, token.getExpiresAt());
	}

	/** Supersedes every open token of a user (an email correction, or a password set another way). */
	@Transactional
	public void invalidateAllOpen(UUID userId) {
		Instant now = Instant.now();
		tokens.findAllUnconsumed(userId).forEach(token -> token.invalidate(now));
	}

	@Transactional(readOnly = true)
	public TokenInfo verify(String rawToken) {
		PasswordToken token = tokens.findByTokenHash(hashOrInvalid(rawToken)).orElseThrow(TokenInvalidException::new);
		TenantUser user = usableUser(token, Instant.now());
		return new TokenInfo(token.getPurpose(), PasswordPolicy.maskEmail(user.getEmail()), token.getExpiresAt());
	}

	@Transactional
	public void complete(String rawToken, String newPassword) {
		PasswordToken token = tokens
			.findByTokenHashAndTenantIdForUpdate(hashOrInvalid(rawToken), tenantContext.getTenantId())
			.orElseThrow(TokenInvalidException::new);
		Instant now = Instant.now();
		TenantUser user = usableUser(token, now);
		passwordPolicy.check(newPassword, user.getEmail(), null);

		token.markUsed(now);
		user.setOwnPassword(passwordEncoder.encode(newPassword), now);
		users.save(user);
		tokens.findAllUnconsumed(user.getId()).forEach(other -> other.invalidate(now));
		refreshTokenService.revokeAllTenantSessions(user.getId(), null);

		String action = (token.getPurpose() == PasswordTokenPurpose.ACCOUNT_SETUP) ? "auth.account_setup_completed"
				: "auth.password_reset_completed";
		auditLogApi.record(new AuditLogEntry(user.getId(), action, "tenant_user", user.getId(), null,
				Map.of("tokenId", token.getId().toString())));
	}

	/** The token must be open and its account still in the state the purpose applies to. */
	private TenantUser usableUser(PasswordToken token, Instant now) {
		if (!token.isOpen(now)) {
			throw new TokenInvalidException();
		}
		TenantUser user = users.findById(token.getUserId()).orElseThrow(TokenInvalidException::new);
		if (!appliesTo(token.getPurpose(), user.getStatus())) {
			throw new TokenInvalidException();
		}
		return user;
	}

	private static boolean appliesTo(PasswordTokenPurpose purpose, AccountStatus status) {
		return switch (purpose) {
			case ACCOUNT_SETUP -> status == AccountStatus.INVITED;
			case PASSWORD_RESET -> status == AccountStatus.ACTIVE;
		};
	}

	private static String newRawToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** A value that can't be a real token (e.g. absurdly long) is simply invalid. */
	private static String hashOrInvalid(String rawToken) {
		if (rawToken == null || rawToken.isBlank() || rawToken.length() > 200) {
			throw new TokenInvalidException();
		}
		return hash(rawToken.trim());
	}

	static String hash(String rawToken) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

}
