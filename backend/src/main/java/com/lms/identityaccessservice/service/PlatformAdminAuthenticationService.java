package com.lms.identityaccessservice.service;

import com.lms.identityaccessservice.config.SessionProperties;
import com.lms.identityaccessservice.domain.AccountStatus;
import com.lms.identityaccessservice.domain.PlatformAdminSession;
import com.lms.identityaccessservice.domain.PlatformAdminUser;
import com.lms.identityaccessservice.error.InvalidCredentialsException;
import com.lms.identityaccessservice.error.UserSuspendedException;
import com.lms.identityaccessservice.repository.PlatformAdminSessionRepository;
import com.lms.identityaccessservice.repository.PlatformAdminUserRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform Admin login - structurally separate from {@link
 * AuthenticationService} (plan §9/§15), never a role branch inside the
 * tenant login path. Same anti-enumeration password-first ordering; see
 * {@link AuthenticationService}'s javadoc for the rationale.
 */
@Service
public class PlatformAdminAuthenticationService {

	private static final String DUMMY_HASH = "$argon2id$v=19$m=19456,t=2,p=1$q/xtH3ADKPiPRDDllEEWiw$RzQB9CCEtUK4oL/qinkaMzicZbpzdV6CeKsIlu3roVI";

	private final PlatformAdminUserRepository platformAdminUserRepository;

	private final PlatformAdminSessionRepository platformAdminSessionRepository;

	private final PasswordEncoder passwordEncoder;

	private final TokenService tokenService;

	private final SessionProperties sessionProperties;

	public PlatformAdminAuthenticationService(PlatformAdminUserRepository platformAdminUserRepository,
			PlatformAdminSessionRepository platformAdminSessionRepository, PasswordEncoder passwordEncoder,
			TokenService tokenService, SessionProperties sessionProperties) {
		this.platformAdminUserRepository = platformAdminUserRepository;
		this.platformAdminSessionRepository = platformAdminSessionRepository;
		this.passwordEncoder = passwordEncoder;
		this.tokenService = tokenService;
		this.sessionProperties = sessionProperties;
	}

	@Transactional
	public LoginResult login(String email, String rawPassword, String deviceIdentifierHash) {
		Optional<PlatformAdminUser> adminOpt = platformAdminUserRepository.findByEmail(email);
		String hashToVerify = adminOpt.map(PlatformAdminUser::getPasswordHash).orElse(DUMMY_HASH);
		boolean passwordMatches = passwordEncoder.matches(rawPassword, hashToVerify);

		if (adminOpt.isEmpty() || !passwordMatches) {
			throw new InvalidCredentialsException();
		}
		PlatformAdminUser admin = adminOpt.get();
		if (admin.getStatus() == AccountStatus.SUSPENDED) {
			throw new UserSuspendedException();
		}

		Instant now = Instant.now();
		String rawRefreshToken = tokenService.generateOpaqueRefreshToken();
		PlatformAdminSession session = new PlatformAdminSession(admin.getId(),
				tokenService.hashRefreshToken(rawRefreshToken), deviceIdentifierHash, now,
				now.plus(sessionProperties.getRefreshTokenTtl()));
		session = platformAdminSessionRepository.save(session);

		String accessToken = tokenService.issuePlatformAdminAccessToken(admin.getId(), session.getId());
		return new LoginResult(accessToken, tokenService.accessTokenTtl(), session.getId(), rawRefreshToken,
				sessionProperties.getRefreshTokenTtl(), admin.isMustChangePassword());
	}

}
