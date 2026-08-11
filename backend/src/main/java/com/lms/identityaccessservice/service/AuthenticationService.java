package com.lms.identityaccessservice.service;

import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.config.SessionProperties;
import com.lms.identityaccessservice.domain.AccountStatus;
import com.lms.identityaccessservice.domain.DeviceSession;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.identityaccessservice.error.InvalidCredentialsException;
import com.lms.identityaccessservice.error.UserSuspendedException;
import com.lms.identityaccessservice.repository.DeviceSessionRepository;
import com.lms.identityaccessservice.repository.TenantUserRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-user login (AUTH-1). Tenant identity is never taken from the
 * request/DTO - {@link TenantContext} is already resolved by {@code
 * TenantResolutionFilter} before this runs, from the subdomain alone.
 *
 * <p>Anti-enumeration ordering (plan §21, closes the enumeration side
 * channel): the password is ALWAYS verified first - against a fixed dummy
 * Argon2id hash when no user matches the email, so response timing does not
 * reveal whether the email exists - and only after a genuine password match
 * is the account's suspension status checked. A wrong password against a
 * suspended account therefore still returns the identical generic
 * {@link InvalidCredentialsException}, never {@link UserSuspendedException}.
 */
@Service
public class AuthenticationService {

	/**
	 * A syntactically valid, but never-assigned, Argon2id hash used purely so
	 * {@link PasswordEncoder#matches} still does real Argon2id work (same
	 * cost as a genuine verification) when no account matches the supplied
	 * email - see class javadoc.
	 */
	private static final String DUMMY_HASH = "$argon2id$v=19$m=19456,t=2,p=1$q/xtH3ADKPiPRDDllEEWiw$RzQB9CCEtUK4oL/qinkaMzicZbpzdV6CeKsIlu3roVI";

	private final TenantUserRepository tenantUserRepository;

	private final DeviceSessionRepository deviceSessionRepository;

	private final PasswordEncoder passwordEncoder;

	private final TokenService tokenService;

	private final SessionProperties sessionProperties;

	private final TenantContext tenantContext;

	public AuthenticationService(TenantUserRepository tenantUserRepository,
			DeviceSessionRepository deviceSessionRepository, PasswordEncoder passwordEncoder,
			TokenService tokenService, SessionProperties sessionProperties, TenantContext tenantContext) {
		this.tenantUserRepository = tenantUserRepository;
		this.deviceSessionRepository = deviceSessionRepository;
		this.passwordEncoder = passwordEncoder;
		this.tokenService = tokenService;
		this.sessionProperties = sessionProperties;
		this.tenantContext = tenantContext;
	}

	@Transactional
	public LoginResult login(String email, String rawPassword, String deviceIdentifierHash) {
		Optional<TenantUser> userOpt = tenantUserRepository.findByEmail(email);
		String hashToVerify = userOpt.map(TenantUser::getPasswordHash).orElse(DUMMY_HASH);
		boolean passwordMatches = passwordEncoder.matches(rawPassword, hashToVerify);

		if (userOpt.isEmpty() || !passwordMatches) {
			throw new InvalidCredentialsException();
		}
		TenantUser user = userOpt.get();
		if (user.getStatus() == AccountStatus.SUSPENDED) {
			throw new UserSuspendedException();
		}

		var tenantId = tenantContext.getTenantId();
		Instant now = Instant.now();
		String rawRefreshToken = tokenService.generateOpaqueRefreshToken();
		DeviceSession session = new DeviceSession(tenantId, user.getId(), tokenService.hashRefreshToken(rawRefreshToken),
				deviceIdentifierHash, now, now.plus(sessionProperties.getRefreshTokenTtl()));
		session = deviceSessionRepository.save(session);

		String accessToken = tokenService.issueTenantAccessToken(user.getId(), tenantId, user.getRole(), session.getId());
		return new LoginResult(accessToken, tokenService.accessTokenTtl(), session.getId(), rawRefreshToken,
				sessionProperties.getRefreshTokenTtl(), user.isMustChangePassword());
	}

}
