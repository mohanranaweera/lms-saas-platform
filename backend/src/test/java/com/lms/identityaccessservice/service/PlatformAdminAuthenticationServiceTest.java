package com.lms.identityaccessservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.identityaccessservice.config.SessionProperties;
import com.lms.identityaccessservice.domain.AccountStatus;
import com.lms.identityaccessservice.domain.PlatformAdminUser;
import com.lms.identityaccessservice.error.InvalidCredentialsException;
import com.lms.identityaccessservice.error.UserSuspendedException;
import com.lms.identityaccessservice.repository.PlatformAdminSessionRepository;
import com.lms.identityaccessservice.repository.PlatformAdminUserRepository;
import java.lang.reflect.Field;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Mockito-only unit tests for {@link PlatformAdminAuthenticationService},
 * mirroring {@link AuthenticationServiceTest}: the same anti-enumeration
 * password-first ordering applies to Platform Admin login, and it must be
 * proven independently since this is a structurally separate service, not a
 * role branch inside {@link AuthenticationService}.
 */
@ExtendWith(MockitoExtension.class)
class PlatformAdminAuthenticationServiceTest {

	/**
	 * Copied from {@code PlatformAdminAuthenticationService.DUMMY_HASH}
	 * (private): a syntactically valid, never-assigned Argon2id hash.
	 * Duplicated here deliberately so the "dummy hash was actually used"
	 * assertion checks a concrete, known value rather than merely "matches
	 * was called with something".
	 */
	private static final String DUMMY_HASH = "$argon2id$v=19$m=19456,t=2,p=1$q/xtH3ADKPiPRDDllEEWiw$RzQB9CCEtUK4oL/qinkaMzicZbpzdV6CeKsIlu3roVI";

	@Mock
	private PlatformAdminUserRepository platformAdminUserRepository;

	@Mock
	private PlatformAdminSessionRepository platformAdminSessionRepository;

	@Mock
	private PasswordEncoder passwordEncoder;

	@Mock
	private TokenService tokenService;

	private SessionProperties sessionProperties;

	private PlatformAdminAuthenticationService platformAdminAuthenticationService;

	@BeforeEach
	void setUp() {
		sessionProperties = new SessionProperties();
		platformAdminAuthenticationService = new PlatformAdminAuthenticationService(platformAdminUserRepository,
				platformAdminSessionRepository, passwordEncoder, tokenService, sessionProperties);
	}

	@Test
	void wrongPasswordForAnExistingActiveAdminThrowsInvalidCredentials() {
		PlatformAdminUser admin = activeAdmin("admin@platform.test", "real-hash");
		when(platformAdminUserRepository.findByEmail("admin@platform.test")).thenReturn(Optional.of(admin));
		when(passwordEncoder.matches("wrong-password", "real-hash")).thenReturn(false);

		assertThatThrownBy(() -> platformAdminAuthenticationService.login("admin@platform.test", "wrong-password",
				"device-hash"))
			.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void noMatchingAdminThrowsInvalidCredentialsButStillRunsThePasswordEncoderAgainstTheDummyHash() {
		when(platformAdminUserRepository.findByEmail("nobody@platform.test")).thenReturn(Optional.empty());
		when(passwordEncoder.matches("any-password", DUMMY_HASH)).thenReturn(false);

		assertThatThrownBy(
				() -> platformAdminAuthenticationService.login("nobody@platform.test", "any-password", "device-hash"))
			.isInstanceOf(InvalidCredentialsException.class);

		// Proves the timing-mitigation branch actually ran Argon2id work against the fixed dummy hash,
		// not that the lookup short-circuited before any password verification happened.
		verify(passwordEncoder, times(1)).matches(eq("any-password"), eq(DUMMY_HASH));
	}

	@Test
	void correctPasswordForASuspendedAdminThrowsUserSuspended() {
		PlatformAdminUser admin = suspendedAdmin("suspended@platform.test", "real-hash");
		when(platformAdminUserRepository.findByEmail("suspended@platform.test")).thenReturn(Optional.of(admin));
		when(passwordEncoder.matches("correct-password", "real-hash")).thenReturn(true);

		assertThatThrownBy(() -> platformAdminAuthenticationService.login("suspended@platform.test",
				"correct-password", "device-hash"))
			.isInstanceOf(UserSuspendedException.class);
	}

	@Test
	void wrongPasswordForASuspendedAdminThrowsInvalidCredentialsNotUserSuspended() {
		// This is the anti-enumeration-ordering test: a wrong password against a suspended
		// admin account must be indistinguishable from a wrong password against an active or
		// nonexistent account - it must NEVER surface UserSuspendedException.
		PlatformAdminUser admin = suspendedAdmin("suspended@platform.test", "real-hash");
		when(platformAdminUserRepository.findByEmail("suspended@platform.test")).thenReturn(Optional.of(admin));
		when(passwordEncoder.matches("wrong-password", "real-hash")).thenReturn(false);

		assertThatThrownBy(() -> platformAdminAuthenticationService.login("suspended@platform.test", "wrong-password",
				"device-hash"))
			.isInstanceOf(InvalidCredentialsException.class)
			.isNotInstanceOf(UserSuspendedException.class);
	}

	@Test
	void suspensionIsNeverCheckedBeforePasswordVerificationSucceeds() {
		// Defense-in-depth: even if the repository is asked, the service must call
		// passwordEncoder.matches before it can possibly branch on suspension status.
		PlatformAdminUser admin = suspendedAdmin("suspended@platform.test", "real-hash");
		when(platformAdminUserRepository.findByEmail("suspended@platform.test")).thenReturn(Optional.of(admin));
		when(passwordEncoder.matches(any(), any())).thenReturn(false);

		assertThatThrownBy(() -> platformAdminAuthenticationService.login("suspended@platform.test", "wrong-password",
				"device-hash"))
			.isInstanceOf(InvalidCredentialsException.class);

		verify(passwordEncoder, times(1)).matches("wrong-password", "real-hash");
	}

	private static PlatformAdminUser activeAdmin(String email, String passwordHash) {
		return new PlatformAdminUser(email, passwordHash);
	}

	private static PlatformAdminUser suspendedAdmin(String email, String passwordHash) {
		PlatformAdminUser admin = new PlatformAdminUser(email, passwordHash);
		setStatus(admin, AccountStatus.SUSPENDED);
		return admin;
	}

	/**
	 * {@link PlatformAdminUser} has no setter for {@code status} by design
	 * (it is always {@code ACTIVE} at construction; suspension is a separate
	 * admin action not modeled by this module) - reflection is used here
	 * purely to build a suspended fixture for this unit test, mirroring
	 * {@link AuthenticationServiceTest}'s equivalent helper.
	 */
	private static void setStatus(PlatformAdminUser admin, AccountStatus status) {
		try {
			Field field = PlatformAdminUser.class.getDeclaredField("status");
			field.setAccessible(true);
			field.set(admin, status);
		}
		catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

}
