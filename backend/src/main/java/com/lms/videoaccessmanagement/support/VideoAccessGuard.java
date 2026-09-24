package com.lms.videoaccessmanagement.support;

import com.lms.common.error.NotFoundException;
import com.lms.contentmanagement.api.MaterialLookupApi;
import com.lms.contentmanagement.api.MaterialVideoOwnership;
import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessState;
import com.lms.enrollmentmanagement.api.EnrollmentAccessStateType;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * The combined staff-matrix-or-Teacher-ownership-or-Student-entitlement
 * check every video-asset read/playback action re-runs, mirroring {@code
 * liveclassmanagement.support.LiveClassAccessGuard}/{@code
 * contentmanagement.material.service.MaterialAccessGuard}'s established
 * pattern exactly (Wave 5, plan §7/§9).
 *
 * <p>Resolves ownership via {@link MaterialLookupApi#resolveVideoAssetOwnership}
 * - the only cross-module dependency permitted on {@code content-management}
 * (never that module's {@code domain}/{@code repository} packages).
 *
 * <p><b>Anti-enumeration rule (plan §7's mandatory cross-tenant test):</b> if
 * {@code videoAssetId} does not resolve to any {@code material} row in the
 * caller's own tenant at all (nonexistent id, cross-tenant id, OR simply "not
 * yet attached to any material" right after upload), this throws {@link
 * NotFoundException} for EVERY role, including Teacher/staff - a video asset
 * id that isn't reachable through the caller's tenant's own material graph
 * does not exist from the caller's point of view. This is a deliberately
 * stricter rule than {@code MaterialAccessGuard}'s (which only applies
 * anti-enumeration to Student), because there is no lesson/course path
 * segment here to already prove tenant membership the way {@code
 * MaterialAccessGuard}'s lesson-id path segment does.
 */
@Component
public class VideoAccessGuard {

	private static final String TEACHER_ROLE = "TEACHER";

	private static final String TEACHER_ASSISTANT_ROLE = "TEACHER_ASSISTANT"; // PROVISIONAL, mirrors MaterialAccessGuard.

	private static final String STUDENT_ROLE = "STUDENT";

	private final MaterialLookupApi materialLookupApi;

	private final EnrollmentAccessApi enrollmentAccessApi;

	private final PermissionCheckService permissionCheckService;

	public VideoAccessGuard(MaterialLookupApi materialLookupApi, EnrollmentAccessApi enrollmentAccessApi,
			PermissionCheckService permissionCheckService) {
		this.materialLookupApi = materialLookupApi;
		this.enrollmentAccessApi = enrollmentAccessApi;
		this.permissionCheckService = permissionCheckService;
	}

	/**
	 * @return the resolved {@link MaterialVideoOwnership} so callers (e.g.
	 * {@code VideoPlaybackSessionService}) don't have to re-resolve it.
	 */
	public MaterialVideoOwnership requireEntitlement(UUID videoAssetId, PermissionAction action) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();

		MaterialVideoOwnership ownership = materialLookupApi.resolveVideoAssetOwnership(videoAssetId)
			.orElseThrow(() -> new NotFoundException("Video not found"));

		if (isTeacherRole(principal)) {
			if (!ownership.teacherId().equals(principal.userId())) {
				throw new AccessDeniedException("You do not have permission to perform this action");
			}
			return ownership;
		}

		if (STUDENT_ROLE.equals(principal.role())) {
			if (action != PermissionAction.VIEW || !ownership.coursePublished()) {
				throw new NotFoundException("Video not found");
			}
			EnrollmentAccessState state = enrollmentAccessApi.resolveAccessState(principal.userId(),
					ownership.courseId());
			if (state.state() != EnrollmentAccessStateType.ACTIVE) {
				throw new NotFoundException("Video not found");
			}
			return ownership;
		}

		permissionCheckService.requirePermission(DomainArea.MATERIALS, action);
		return ownership;
	}

	public boolean isCurrentPrincipalStudent() {
		return STUDENT_ROLE.equals(AuthenticatedPrincipalHolder.get().role());
	}

	public boolean isCurrentPrincipalTeacher() {
		return isTeacherRole(AuthenticatedPrincipalHolder.get());
	}

	private boolean isTeacherRole(AuthenticatedPrincipal principal) {
		return TEACHER_ROLE.equals(principal.role()) || TEACHER_ASSISTANT_ROLE.equals(principal.role());
	}

}
