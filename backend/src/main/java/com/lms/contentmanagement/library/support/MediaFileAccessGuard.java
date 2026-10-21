package com.lms.contentmanagement.library.support;

import com.lms.common.error.NotFoundException;
import com.lms.contentmanagement.library.domain.MediaFile;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Authorization for the institute media file library (R2-D1):
 * <ul>
 * <li>Student: 403 on every library endpoint - students reach library files
 * only through lesson materials, gated by the lesson access evaluator.</li>
 * <li>Teacher / Teacher Assistant: list, open, upload and attach any
 * institute file; rename / delete only their own uploads - another
 * teacher's file is reported as not found (404), never 403, so ownership is
 * not disclosed.</li>
 * <li>Every other staff role: {@code MATERIALS} VIEW / CREATE_EDIT / DELETE
 * from the permission matrix (Tenant Admin holds all three).</li>
 * </ul>
 * Tenant isolation is structural (tenant-aware repository); this guard only
 * decides what the caller may do inside their own tenant.
 */
@Component
public class MediaFileAccessGuard {

	private static final String TEACHER_ROLE = "TEACHER";

	private static final String TEACHER_ASSISTANT_ROLE = "TEACHER_ASSISTANT";

	private static final String STUDENT_ROLE = "STUDENT";

	private final PermissionCheckService permissionCheckService;

	public MediaFileAccessGuard(PermissionCheckService permissionCheckService) {
		this.permissionCheckService = permissionCheckService;
	}

	/** Endpoint-level gate: may the caller perform {@code action} on the library at all? */
	public void requireLibraryAccess(PermissionAction action) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (STUDENT_ROLE.equals(principal.role())) {
			throw new AccessDeniedException("You do not have permission to perform this action");
		}
		if (isTeacher(principal)) {
			return;
		}
		permissionCheckService.requirePermission(DomainArea.MATERIALS, action);
	}

	/**
	 * Item-level gate for rename ({@code CREATE_EDIT}) / delete ({@code
	 * DELETE}): runs {@link #requireLibraryAccess} and, for a teacher, requires
	 * that they uploaded {@code file} (else 404).
	 */
	public void requireManage(MediaFile file, PermissionAction action) {
		requireLibraryAccess(action);
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (isTeacher(principal) && !principal.userId().equals(file.getUploadedBy())) {
			throw new NotFoundException("Media file not found");
		}
	}

	/** Per-request view of what the caller may do with each listed file (server-computed canEdit/canDelete). */
	public CallerRights callerRights() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (isTeacher(principal)) {
			return new CallerRights(principal.userId(), false, false);
		}
		return new CallerRights(null,
				permissionCheckService.hasPermission(DomainArea.MATERIALS, PermissionAction.CREATE_EDIT),
				permissionCheckService.hasPermission(DomainArea.MATERIALS, PermissionAction.DELETE));
	}

	private static boolean isTeacher(AuthenticatedPrincipal principal) {
		return TEACHER_ROLE.equals(principal.role()) || TEACHER_ASSISTANT_ROLE.equals(principal.role());
	}

	/**
	 * @param teacherUserId set for a teacher caller (rights = own uploads only)
	 */
	public record CallerRights(java.util.UUID teacherUserId, boolean staffCanEdit, boolean staffCanDelete) {

		public boolean canEdit(MediaFile file) {
			return teacherUserId != null ? teacherUserId.equals(file.getUploadedBy()) : staffCanEdit;
		}

		public boolean canDelete(MediaFile file) {
			return teacherUserId != null ? teacherUserId.equals(file.getUploadedBy()) : staffCanDelete;
		}

	}

}
