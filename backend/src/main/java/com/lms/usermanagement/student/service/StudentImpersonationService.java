package com.lms.usermanagement.student.service;

import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.ImpersonationApi;
import com.lms.identityaccessservice.api.ImpersonationApi.StartedImpersonation;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.usermanagement.student.domain.StudentProfile;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-033 "Log as Student" start: resolves the target student profile in the caller's
 * tenant (another tenant's id is a 404), refuses pending / removed students, then
 * delegates to identity's {@link ImpersonationApi}, which re-checks the permission,
 * refuses nested sessions and non-active / non-student accounts, replaces the admin's
 * previous session and audits the start - all in this one transaction.
 */
@Service
public class StudentImpersonationService {

	private final StudentManagementService studentManagementService;

	private final ImpersonationApi impersonationApi;

	private final PermissionCheckService permissionCheckService;

	public StudentImpersonationService(StudentManagementService studentManagementService,
			ImpersonationApi impersonationApi, PermissionCheckService permissionCheckService) {
		this.studentManagementService = studentManagementService;
		this.impersonationApi = impersonationApi;
		this.permissionCheckService = permissionCheckService;
	}

	public record Started(StartedImpersonation session, UUID studentId, String indexNumber, String fullName) {
	}

	@Transactional
	public Started start(UUID studentId, String reason) {
		permissionCheckService.requirePermission(DomainArea.STUDENT_IMPERSONATION, PermissionAction.CREATE_EDIT);
		StudentProfile profile = studentManagementService.requireManagedStudent(studentId);
		if (profile.isPendingApproval()) {
			throw StudentManagementService.notActive("A student awaiting approval cannot be viewed in a support session");
		}
		StartedImpersonation session = impersonationApi.start(profile.getUserId(), profile.getId(),
				profile.getIndexNumber(), profile.getName(), reason);
		return new Started(session, profile.getId(), profile.getIndexNumber(), profile.getName());
	}

}
