package com.lms.identityaccessservice.web;

import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.RequiresCapability;
import com.lms.identityaccessservice.service.CapabilityElevationHolder;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints for {@code CapabilityEnforcementIntegrationTest} (STAFF-MGMT-02 §2.3).
 * Lives under {@code /api/**} so the capability interceptor applies.
 */
@RestController
@RequestMapping("/api/test/capability")
class CapabilityTestController {

	private final PermissionCheckService permissionCheckService;

	CapabilityTestController(PermissionCheckService permissionCheckService) {
		this.permissionCheckService = permissionCheckService;
	}

	/** Annotated + the existing static gate: passes for an Operator only via elevation. */
	@GetMapping("/annotated")
	@RequiresCapability(Capability.STUDENTS_VIEW)
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'VIEW')")
	public Map<String, Object> annotated() {
		// Service-layer style check inside the handler sees the elevation too.
		permissionCheckService.requirePermission(com.lms.identityaccessservice.api.DomainArea.STUDENTS,
				com.lms.identityaccessservice.api.PermissionAction.VIEW);
		return Map.of("elevated", CapabilityElevationHolder.get().size(), "studentsCreateEdit",
				permissionCheckService.hasPermission("STUDENTS", "CREATE_EDIT"));
	}

	/** Same gate, no annotation: Operator/Manager fail closed. */
	@GetMapping("/plain")
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'VIEW')")
	public Map<String, Object> plain() {
		return Map.of("ok", true);
	}

	/** Teacher-configurable capability without any static gate. */
	@GetMapping("/teacher-courses")
	@RequiresCapability(Capability.COURSES_VIEW)
	@PreAuthorize("isAuthenticated()")
	public Map<String, Object> teacherCourses() {
		return Map.of("ok", true, "elevated", CapabilityElevationHolder.get().size());
	}

}
