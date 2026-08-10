package com.lms.identityaccessservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.config.SessionProperties;
import com.lms.identityaccessservice.domain.AccountStatus;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.identityaccessservice.error.InvalidCredentialsException;
import com.lms.identityaccessservice.error.UserSuspendedException;
import com.lms.identityaccessservice.repository.DeviceSessionRepository;
import com.lms.identityaccessservice.repository.TenantUserRepository;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Mockito-only unit tests for the anti-enumeration ordering that is this
 * module's single most security-critical piece of logic: the password is
 * always verified first (against a fixed dummy hash when no user matches),
 * and only a genuine password match ever reaches the suspension check.
 */
@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

	/**
	 * Copied from {@code AuthenticationService.DUMMY_HASH} (private): a
	 * syntactically valid, never-assigned Argon2id hash. Duplicated here
	 * deliberately so the "dummy hash was actually used" assertion checks a
	 * concrete, known value rather than merely "matches was called with
	 * something".
	 */
	private static final String DUMMY_HASH = "$argon2id$v=19$m=19456,t=2,p=1$q/xtH3ADKPiPRDDllEEWiw$RzQB9CCEtUK4oL/qinkaMzicZbpzdV6CeKsIlu3roVI";

	private static final UUID TENANT_ID = UUID.randomUUID();

	@Mock
	private TenantUserRepository tenantUserRepository;

	@Mock
	private DeviceSessionRepository deviceSessionRepository;

	@Mock
	private PasswordEncoder passwordEncoder;

	@Mock
	private TokenService tokenService;

	@Mock
	private TenantContext tenantContext;

	private SessionProperties sessionProperties;

	private AuthenticationService authenticationService;

	@BeforeEach
	void setUp() {
		sessionProperties = new SessionProperties();
		authenticationService = new AuthenticationService(tenantUserRepository, deviceSessionRepository,
				passwordEncoder, tokenService, sessionProperties, tenantContext);
	}

	@Test
	void wrongPasswordForAnExistingActiveUserThrowsInvalidCredentials() {
		TenantUser user = activeUser("student@tenant-a.test", "real-hash");
		when(tenantUserRepository.findByEmail("student@tenant-a.test")).thenReturn(Optional.of(user));
		when(passwordEncoder.matches("wrong-password", "real-hash")).thenReturn(false);

		assertThatThrownBy(
				() -> authenticationService.login("student@tenant-a.test", "wrong-password", "device-hash"))
			.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void noMatchingUserThrowsInvalidCredentialsButStillRunsThePasswordEncoderAgainstTheDummyHash() {
		when(tenantUserRepository.findByEmail("nobody@tenant-a.test")).thenReturn(Optional.empty());
		when(passwordEncoder.matches("any-password", DUMMY_HASH)).thenReturn(false);

		assertThatThrownBy(() -> authenticationService.login("nobody@tenant-a.test", "any-password", "device-hash"))
			.isInstanceOf(InvalidCredentialsException.class);

		// Proves the timing-mitigation branch actually ran Argon2id work against the fixed dummy hash,
		// not that the lookup short-circuited before any password verification happened.
		verify(passwordEncoder, times(1)).matches(eq("any-password"), eq(DUMMY_HASH));
	}

	@Test
	void correctPasswordForASuspendedUserThrowsUserSuspended() {
		TenantUser user = suspendedUser("suspended@tenant-a.test", "real-hash");
		when(tenantUserRepository.findByEmail("suspended@tenant-a.test")).thenReturn(Optional.of(user));
		when(passwordEncoder.matches("correct-password", "real-hash")).thenReturn(true);

		assertThatThrownBy(
				() -> authenticationService.login("suspended@tenant-a.test", "correct-password", "device-hash"))
			.isInstanceOf(UserSuspendedException.class);
	}

	@Test
	void wrongPasswordForASuspendedUserThrowsInvalidCredentialsNotUserSuspended() {
		// This is the anti-enumeration-ordering test: a wrong password against a suspended
		// account must be indistinguishable from a wrong password against an active or
		// nonexistent account - it must NEVER surface UserSuspendedException.
		TenantUser user = suspendedUser("suspended@tenant-a.test", "real-hash");
		when(tenantUserRepository.findByEmail("suspended@tenant-a.test")).thenReturn(Optional.of(user));
		when(passwordEncoder.matches("wrong-password", "real-hash")).thenReturn(false);

		assertThatThrownBy(
				() -> authenticationService.login("suspended@tenant-a.test", "wrong-password", "device-hash"))
			.isInstanceOf(InvalidCredentialsException.class)
			.isNotInstanceOf(UserSuspendedException.class);
	}

	@Test
	void suspensionIsNeverCheckedBeforePasswordVerificationSucceeds() {
		// Defense-in-depth: even if the repository is asked, the service must call
		// passwordEncoder.matches before it can possibly branch on suspension status.
		TenantUser user = suspendedUser("suspended@tenant-a.test", "real-hash");
		when(tenantUserRepository.findByEmail("suspended@tenant-a.test")).thenReturn(Optional.of(user));
		when(passwordEncoder.matches(any(), any())).thenReturn(false);

		assertThatThrownBy(
				() -> authenticationService.login("suspended@tenant-a.test", "wrong-password", "device-hash"))
			.isInstanceOf(InvalidCredentialsException.class);

		verify(passwordEncoder, times(1)).matches("wrong-password", "real-hash");
	}

	private static TenantUser activeUser(String email, String passwordHash) {
		return new TenantUser(TENANT_ID, email, passwordHash, Role.STUDENT);
	}

	private static TenantUser suspendedUser(String email, String passwordHash) {
		TenantUser user = new TenantUser(TENANT_ID, email, passwordHash, Role.STUDENT);
		setStatus(user, AccountStatus.SUSPENDED);
		return user;
	}

	/**
	 * {@link TenantUser} has no setter for {@code status} by design (it is
	 * always {@code ACTIVE} at construction; suspension is a separate admin
	 * action not modeled by this module) - reflection is used here purely to
	 * build a suspended fixture for this unit test, mirroring how the
	 * Testcontainers integration tests seed a suspended row via raw SQL
	 * instead of an entity setter.
	 */
	private static void setStatus(TenantUser user, AccountStatus status) {
		try {
			Field field = TenantUser.class.getDeclaredField("status");
			field.setAccessible(true);
			field.set(user, status);
		}
		catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

}
