package com.lms.identityaccessservice.service.permission;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.repository.CapabilityGrantStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * STAFF-MGMT-02 §2.6 delegation guards of {@link RolePermissionMatrixService} that cannot be
 * reached over HTTP today (only Tenant Admin holds STAFF_PERMISSIONS_EDIT): an editor can never
 * change its own role's permissions, never grant a capability it does not hold, and nothing is
 * written or audited when a guard fails.
 */
class RolePermissionMatrixServiceTest {

	private final CapabilityGrantStore store = mock(CapabilityGrantStore.class);

	private final PermissionCheckService permissions = mock(PermissionCheckService.class);

	private final AuditLogApi audit = mock(AuditLogApi.class);

	private final RolePermissionMatrixService service = new RolePermissionMatrixService(store, permissions, audit,
			mock(TenantContext.class));

	@AfterEach
	void clear() {
		AuthenticatedPrincipalHolder.clear();
	}

	private void actingAs(String role) {
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), UUID.randomUUID(), role,
				UUID.randomUUID()));
		when(store.findAllOverrides()).thenReturn(Map.of());
	}

	@Test
	void anEditorCannotChangeItsOwnRole() {
		actingAs("MANAGER");
		when(permissions.hasCapability(any(Capability.class))).thenReturn(true);

		assertThatThrownBy(() -> service.update(0L,
				List.of(new PermissionChange("MANAGER", "STUDENTS_VIEW", true))))
			.isInstanceOf(AccessDeniedException.class);
		verify(store, never()).advanceVersion(anyLong(), any());
		verify(store, never()).upsert(anyString(), anyString(), anyBoolean(), any());
		verifyNoInteractions(audit);
	}

	@Test
	void anEditorCannotGrantACapabilityItDoesNotHold() {
		actingAs("TENANT_ADMIN");
		when(permissions.hasCapability(Capability.STUDENTS_DELETE)).thenReturn(false);

		assertThatThrownBy(() -> service.update(0L,
				List.of(new PermissionChange("OPERATOR", "STUDENTS_DELETE", true))))
			.isInstanceOf(AccessDeniedException.class);
		verify(store, never()).upsert(anyString(), anyString(), anyBoolean(), any());
		verifyNoInteractions(audit);
	}

	@Test
	void withoutTheEditCapabilityNothingIsRead() {
		actingAs("READ_ONLY_AUDITOR");
		doThrow(new AccessDeniedException("no")).when(permissions).requireCapability(Capability.STAFF_PERMISSIONS_EDIT);

		assertThatThrownBy(() -> service.update(0L, List.of())).isInstanceOf(AccessDeniedException.class);
		verify(store, never()).findAllOverrides();
		verifyNoInteractions(audit);
	}

}
