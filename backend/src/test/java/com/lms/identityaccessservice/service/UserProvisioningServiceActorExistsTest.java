package com.lms.identityaccessservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lms.common.tenant.TenantContext;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.identityaccessservice.domain.PlatformAdminUser;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.identityaccessservice.repository.PlatformAdminUserRepository;
import com.lms.identityaccessservice.repository.TenantUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Plain Mockito unit test for {@link UserProvisioningService#actorExists},
 * added for {@code audit-log-management}'s C1 actor-existence guard (see
 * {@code AuditLogService#requireKnownActor}). Covers the dual-path
 * resolution this method mirrors from {@code JwtAuthenticationFilter}: a
 * {@code tenant_user} check when a {@link TenantContextHolder} value is set,
 * a {@code platform_admin_user} check when it is not.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserProvisioningServiceActorExistsTest {

	@Mock
	private TenantUserRepository tenantUserRepository;

	@Mock
	private PlatformAdminUserRepository platformAdminUserRepository;

	@Mock
	private PasswordEncoder passwordEncoder;

	@Mock
	private TenantContext tenantContext;

	private UserProvisioningService service;

	@BeforeEach
	void setUp() {
		service = new UserProvisioningService(tenantUserRepository, platformAdminUserRepository, passwordEncoder,
				tenantContext);
	}

	@AfterEach
	void clearTenantContextHolder() {
		TenantContextHolder.clear();
	}

	@Test
	void whenATenantContextIsResolvedChecksTenantUserAndNeverPlatformAdminUser() {
		TenantContextHolder.set(UUID.randomUUID());
		UUID actorId = UUID.randomUUID();
		when(tenantUserRepository.findById(actorId))
			.thenReturn(Optional.of(new TenantUser(UUID.randomUUID(), "actor@example.test", "hash", Role.TEACHER)));

		boolean result = service.actorExists(actorId);

		assertThat(result).isTrue();
		verifyNoInteractions(platformAdminUserRepository);
	}

	@Test
	void whenATenantContextIsResolvedAndTheIdIsNotAKnownTenantUserReturnsFalse() {
		TenantContextHolder.set(UUID.randomUUID());
		UUID actorId = UUID.randomUUID();
		when(tenantUserRepository.findById(actorId)).thenReturn(Optional.empty());

		assertThat(service.actorExists(actorId)).isFalse();
	}

	@Test
	void whenNoTenantContextIsResolvedChecksPlatformAdminUserAndNeverTenantUser() {
		assertThat(TenantContextHolder.isSet()).isFalse();
		UUID actorId = UUID.randomUUID();
		PlatformAdminUser admin = new PlatformAdminUser("admin@platform.test", "hash");
		when(platformAdminUserRepository.findById(actorId)).thenReturn(Optional.of(admin));

		boolean result = service.actorExists(actorId);

		assertThat(result).isTrue();
		verifyNoInteractions(tenantUserRepository);
	}

	@Test
	void whenNoTenantContextIsResolvedAndTheIdIsNotAKnownPlatformAdminReturnsFalse() {
		assertThat(TenantContextHolder.isSet()).isFalse();
		UUID actorId = UUID.randomUUID();
		when(platformAdminUserRepository.findById(actorId)).thenReturn(Optional.empty());

		assertThat(service.actorExists(actorId)).isFalse();
	}

}
