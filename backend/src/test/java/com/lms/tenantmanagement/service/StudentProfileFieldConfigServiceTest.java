package com.lms.tenantmanagement.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.tenantmanagement.api.TenantConfigApi;
import com.lms.tenantmanagement.repository.StudentProfileFieldSettingRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * STU-PROF-01 security review S-2: the settings service re-checks
 * {@code BRANDING_SETTINGS} itself (defense in depth), so a caller that
 * bypasses the controller's {@code @PreAuthorize} still cannot read or change
 * the settings.
 */
class StudentProfileFieldConfigServiceTest {

	private final StudentProfileFieldSettingRepository repository = mock(StudentProfileFieldSettingRepository.class);

	private final TenantConfigApi tenantConfigApi = mock(TenantConfigApi.class);

	private final AuditLogApi auditLogApi = mock(AuditLogApi.class);

	private final PermissionCheckService permissions = mock(PermissionCheckService.class);

	private final StudentProfileFieldConfigService service = new StudentProfileFieldConfigService(repository,
			tenantConfigApi, mock(TenantContext.class), auditLogApi, permissions);

	@Test
	void updateIsRefusedWithoutCreateEditAndWritesNothing() {
		doThrow(new AccessDeniedException("denied")).when(permissions)
			.requirePermission(DomainArea.BRANDING_SETTINGS, PermissionAction.CREATE_EDIT);

		assertThatThrownBy(() -> service
			.updateFieldPolicies(List.of(new StudentProfileFieldSettingChange("NIC_NUMBER", true, false, false))))
			.isInstanceOf(AccessDeniedException.class);
		verifyNoInteractions(repository, auditLogApi);
	}

	@Test
	void settingsReadIsRefusedWithoutView() {
		doThrow(new AccessDeniedException("denied")).when(permissions)
			.requirePermission(DomainArea.BRANDING_SETTINGS, PermissionAction.VIEW);

		assertThatThrownBy(service::getSettings).isInstanceOf(AccessDeniedException.class);
		verifyNoInteractions(repository);
	}

}
