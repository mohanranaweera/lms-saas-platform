package com.lms.identityaccessservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.CapabilityAvailability;
import com.lms.identityaccessservice.api.CapabilityGroup;
import com.lms.identityaccessservice.api.ConfigurableRole;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionPair;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.repository.CapabilityGrantStore;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * "View Course Payment Status" (owner, 2026-11-10): who may see the course roster's
 * paid/Free Card status. Static holders are the roles that already see unpaid students
 * (FINANCE_EXPENSES/VIEW, as the Unpaid Students report); a Teacher holds it only after
 * the tenant grants it, and a grant never elevates the static finance gate.
 */
class CoursePaymentStatusCapabilityTest {

	private static final UUID TENANT = UUID.randomUUID();

	private final CapabilityGrantStore store = mock(CapabilityGrantStore.class);

	private final PermissionCheckServiceImpl service = new PermissionCheckServiceImpl(store);

	@AfterEach
	void clear() {
		AuthenticatedPrincipalHolder.clear();
		TenantContextHolder.clear();
	}

	private void as(Role role) {
		TenantContextHolder.set(TENANT);
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT, role.name(),
				UUID.randomUUID()));
	}

	@Test
	void isAnEnforcedCoursesCapabilityGatedLikeTheUnpaidStudentsReport() {
		Capability capability = Capability.COURSES_VIEW_PAYMENT_STATUS;

		assertThat(capability.group()).isEqualTo(CapabilityGroup.COURSES);
		assertThat(capability.availability()).isEqualTo(CapabilityAvailability.ENFORCED);
		assertThat(capability.primary())
			.isEqualTo(PermissionPair.of(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW))
			.isEqualTo(Capability.REPORTS_UNPAID_STUDENTS.primary());
		assertThat(capability.isConfigurableFor(ConfigurableRole.TEACHER)).isTrue();
		assertThat(capability.teacherDefaultGranted()).isFalse();
	}

	@Test
	void tenantAdminHoldsItAndAStudentNever() {
		as(Role.TENANT_ADMIN);
		assertThat(service.hasCapability(Capability.COURSES_VIEW_PAYMENT_STATUS)).isTrue();

		as(Role.STUDENT);
		assertThat(service.hasCapability(Capability.COURSES_VIEW_PAYMENT_STATUS)).isFalse();
	}

	@Test
	void aTeacherHoldsItOnlyAfterATenantGrantAndTheGrantNeverElevatesFinance() {
		as(Role.TEACHER);
		when(store.findOverridesForRole("TEACHER")).thenReturn(Map.of());
		assertThat(service.hasCapability(Capability.COURSES_VIEW_PAYMENT_STATUS)).isFalse();

		when(store.findOverridesForRole("TEACHER")).thenReturn(Map.of("COURSES_VIEW_PAYMENT_STATUS", true));
		assertThat(service.hasCapability(Capability.COURSES_VIEW_PAYMENT_STATUS)).isTrue();
		assertThat(service.hasPermission(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW)).isFalse();
	}

}
