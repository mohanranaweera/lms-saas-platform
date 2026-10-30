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

	private final DeviceRegistrationService deviceRegistrationService;

	public AuthenticationService(TenantUserRepository tenantUserRepository,
			DeviceSessionRepository deviceSessionRepository, PasswordEncoder passwordEncoder,
			TokenService tokenService, SessionProperties sessionProperties, TenantContext tenantContext,
			DeviceRegistrationService deviceRegistrationService) {
		this.tenantUserRepository = tenantUserRepository;
		this.deviceSessionRepository = deviceSessionRepository;
		this.passwordEncoder = passwordEncoder;
		this.tokenService = tokenService;
		this.sessionProperties = sessionProperties;
		this.tenantContext = tenantContext;
		this.deviceRegistrationService = deviceRegistrationService;
	}

	/**
	 * Client signals the device binding needs (Wave 10): the raw {@code
	 * lms_device_id} cookie value (null on a first visit), and the User-Agent/IP
	 * used only for the display label and last-seen IP - never as identity.
	 */
	public record DeviceContext(String rawDeviceToken, String userAgent, String ipAddress) {
	}

	/**
	 * Wave 10: once the credential checks pass, the login is bound to a
	 * server-issued device ({@link DeviceRegistrationService#bindAtLogin}); a
	 * student over the device limit is refused here with {@code 403
	 * DEVICE_LIMIT_REACHED} in the same transaction, so nothing is persisted.
	 */
	@Transactional
	public LoginResult login(String email, String rawPassword, DeviceContext device) {
		return complete(tenantUserRepository.findByEmail(email), rawPassword, device, InvalidCredentialsException::new);
	}

	/**
	 * ADR-031: sign-in by mobile. {@code e164} is the already-normalised
	 * number, or {@code null} when what the user typed isn't a valid number -
	 * the password is still verified against the dummy hash so the response
	 * and its timing match a wrong password. Everything after the user is
	 * found is the same path as email sign-in.
	 */
	@Transactional
	public LoginResult loginWithMobile(String e164, String rawPassword, DeviceContext device) {
		Optional<TenantUser> userOpt = e164 == null ? Optional.empty() : tenantUserRepository.findByLoginMobile(e164);
		return complete(userOpt, rawPassword, device, InvalidCredentialsException::forMobile);
	}

	private LoginResult complete(Optional<TenantUser> userOpt, String rawPassword, DeviceContext device,
			java.util.function.Supplier<InvalidCredentialsException> invalid) {
		String hashToVerify = userOpt.map(TenantUser::getPasswordHash).orElse(DUMMY_HASH);
		boolean passwordMatches = passwordEncoder.matches(rawPassword, hashToVerify);

		if (userOpt.isEmpty() || !passwordMatches) {
			throw invalid.get();
		}
		TenantUser user = userOpt.get();
		if (user.getStatus() == AccountStatus.INVITED) {
			// ADR-020: indistinguishable from a wrong password (no account-state disclosure).
			throw invalid.get();
		}
		if (user.getStatus() == AccountStatus.SUSPENDED) {
			throw new UserSuspendedException();
		}

		var tenantId = tenantContext.getTenantId();
		Instant now = Instant.now();
		DeviceRegistrationService.DeviceBinding binding = deviceRegistrationService.bindAtLogin(user,
				device.rawDeviceToken(), device.userAgent(), device.ipAddress());
		String rawRefreshToken = tokenService.generateOpaqueRefreshToken();
		DeviceSession session = new DeviceSession(tenantId, user.getId(), tokenService.hashRefreshToken(rawRefreshToken),
				binding.tokenHash(), now, now.plus(sessionProperties.getRefreshTokenTtl()), binding.deviceId());
		session = deviceSessionRepository.save(session);

		String accessToken = tokenService.issueTenantAccessToken(user.getId(), tenantId, user.getRole(), session.getId());
		return new LoginResult(accessToken, tokenService.accessTokenTtl(), session.getId(), rawRefreshToken,
				sessionProperties.getRefreshTokenTtl(), user.isMustChangePassword(), binding.rawToken());
	}

}
