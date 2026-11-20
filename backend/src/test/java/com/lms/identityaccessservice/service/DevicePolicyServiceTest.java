package com.lms.identityaccessservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.DeviceLimitSource;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.config.DeviceProperties;
import com.lms.identityaccessservice.domain.StudentDeviceLimitOverride;
import com.lms.identityaccessservice.domain.TenantDevicePolicy;
import com.lms.identityaccessservice.repository.StudentDeviceLimitOverrideRepository;
import com.lms.identityaccessservice.repository.TenantDevicePolicyRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Wave 10 device-limit precedence (authentication-authorization.md §6): the
 * most specific configured level wins - student, course, tenant, plan - and
 * the plan default is 2 (product-owner decision D2).
 */
class DevicePolicyServiceTest {

	private static final UUID TENANT = UUID.randomUUID();

	private static final UUID USER = UUID.randomUUID();

	private final TenantDevicePolicyRepository policyRepository = mock(TenantDevicePolicyRepository.class);

	private final StudentDeviceLimitOverrideRepository overrideRepository = mock(
			StudentDeviceLimitOverrideRepository.class);

	private DevicePolicyService service;

	@BeforeEach
	void setUp() {
		service = new DevicePolicyService(policyRepository, overrideRepository, new DeviceProperties(),
				mock(TenantContext.class), mock(PermissionCheckService.class), mock(AuditLogApi.class));
		when(policyRepository.findForCurrentTenant()).thenReturn(Optional.empty());
		when(overrideRepository.findByUserId(USER)).thenReturn(Optional.empty());
	}

	@Test
	void thePlanDefaultIsTwoDevices() {
		assertThat(service.resolveLoginLimit(USER))
			.isEqualTo(new DevicePolicyService.ResolvedLimit(2, DeviceLimitSource.PLAN));
	}

	@Test
	void aTenantLimitBeatsThePlanDefault() {
		tenantLimit(4);
		assertThat(service.resolveLoginLimit(USER))
			.isEqualTo(new DevicePolicyService.ResolvedLimit(4, DeviceLimitSource.TENANT));
	}

	@Test
	void aCourseLimitBeatsTheTenantLimit() {
		tenantLimit(4);
		assertThat(service.resolve(USER, 1)).isEqualTo(new DevicePolicyService.ResolvedLimit(1, DeviceLimitSource.COURSE));
	}

	@Test
	void aStudentExceptionBeatsEveryOtherLevel() {
		tenantLimit(4);
		StudentDeviceLimitOverride override = new StudentDeviceLimitOverride(TENANT, USER);
		override.set(3, "approved", UUID.randomUUID(), Instant.now());
		when(overrideRepository.findByUserId(USER)).thenReturn(Optional.of(override));

		assertThat(service.resolve(USER, 1)).isEqualTo(new DevicePolicyService.ResolvedLimit(3, DeviceLimitSource.STUDENT));
		assertThat(service.resolveLoginLimit(USER))
			.isEqualTo(new DevicePolicyService.ResolvedLimit(3, DeviceLimitSource.STUDENT));
	}

	@Test
	void anUnsavedTenantPolicyDefaultsToA24HourCooldownWithSelfServiceOff() {
		DevicePolicyService.EffectivePolicy policy = service.currentPolicy();
		assertThat(policy.tenantMaxDevices()).isNull();
		assertThat(policy.resetCooldownHours()).isEqualTo(24);
		assertThat(policy.studentSelfServiceEnabled()).isFalse();
	}

	private void tenantLimit(int max) {
		TenantDevicePolicy policy = new TenantDevicePolicy(TENANT);
		policy.update(max, 24, false, UUID.randomUUID(), Instant.now());
		when(policyRepository.findForCurrentTenant()).thenReturn(Optional.of(policy));
	}

}
