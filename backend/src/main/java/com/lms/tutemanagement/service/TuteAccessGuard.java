package com.lms.tutemanagement.service;

import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * TUTE-01 section 8 authorization, re-run by every service entry point behind the
 * {@code @RequiresCapability} gate. The exact capability is re-checked per request (so a revoke
 * applies on the next request); a Teacher (default not granted) is additionally limited to
 * courses whose {@code course.teacher_id} is the caller. Course resolution mirrors {@code
 * CourseAccessGuard}: an unknown / cross-tenant course is 404 before anything else, a foreign
 * course of the same tenant is 403 for a Teacher.
 */
@Component
public class TuteAccessGuard {

	static final String TEACHER_ROLE = "TEACHER";

	private final CourseLookupApi courseLookupApi;

	private final PermissionCheckService permissionCheckService;

	public TuteAccessGuard(CourseLookupApi courseLookupApi, PermissionCheckService permissionCheckService) {
		this.courseLookupApi = courseLookupApi;
		this.permissionCheckService = permissionCheckService;
	}

	/** 404 for an unknown / cross-tenant course, 403 without {@code capability} or (Teacher) ownership. */
	public void requireCourse(UUID courseId, Capability capability) {
		UUID teacherId = courseLookupApi.getTeacherId(courseId)
			.orElseThrow(() -> new NotFoundException("Course not found"));
		permissionCheckService.requireCapability(capability);
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (TEACHER_ROLE.equals(principal.role()) && !teacherId.equals(principal.userId())) {
			throw new AccessDeniedException("You do not have permission to perform this action");
		}
	}

	/**
	 * Requires {@code capability} and returns the caller's course scope for tenant-wide reads:
	 * {@code null} = every course of the tenant; a Teacher gets the courses they teach.
	 */
	public Set<UUID> requireScope(Capability capability) {
		permissionCheckService.requireCapability(capability);
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (TEACHER_ROLE.equals(principal.role())) {
			return courseLookupApi.listCourseSummariesByTeacherId(principal.userId())
				.stream()
				.map(CourseSummary::id)
				.collect(Collectors.toUnmodifiableSet());
		}
		return null;
	}

	/** {@code true} when {@code courseId} is inside {@code scope} ({@code null} = everything). */
	static boolean inScope(Set<UUID> scope, UUID courseId) {
		return scope == null || scope.contains(courseId);
	}

	static UUID actorId() {
		return AuthenticatedPrincipalHolder.get().userId();
	}

}
