package com.lms.usermanagement.teacher.web;

import com.lms.common.api.ApiResponse;
import com.lms.usermanagement.teacher.domain.ApprovalStatus;
import com.lms.usermanagement.teacher.service.TeacherAccount;
import com.lms.usermanagement.teacher.service.TeacherService;
import com.lms.usermanagement.teacher.web.dto.TeacherCreateRequest;
import com.lms.usermanagement.teacher.web.dto.TeacherResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Teacher Management (MVP-007, {@code TCH-1}) endpoints. Every method is
 * gated by {@code PermissionCheckService}'s {@code TEACHERS} domain area,
 * matching {@code docs/requirements/user-roles-and-permissions.md} §2's
 * matrix (Tenant Admin: view/create/edit/delete; Course Coordinator:
 * view/create/edit; Student Support and Read-only Auditor: view only) - stays
 * thin, delegates entirely to {@link TeacherService}. The Tenant-Admin-only
 * narrowing on approve/reject (approved plan §2/§21-item-1) happens in the
 * service layer, not here, since {@code PermissionCheckService} has no finer
 * primitive for "same domain, higher-trust action, different actor" than
 * {@code CREATE_EDIT} already provides.
 */
@RestController
@RequestMapping("/api/v1/teachers")
public class TeacherController {

	private final TeacherService teacherService;

	public TeacherController(TeacherService teacherService) {
		this.teacherService = teacherService;
	}

	@PostMapping
	@PreAuthorize("@permissionCheckService.hasPermission('TEACHERS', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<TeacherResponse>> createTeacher(
			@Valid @RequestBody TeacherCreateRequest request) {
		TeacherAccount account = teacherService.createTeacher(request.name(), request.email(), request.password());
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toResponse(account)));
	}

	@GetMapping
	@PreAuthorize("@permissionCheckService.hasPermission('TEACHERS', 'VIEW')")
	public ResponseEntity<ApiResponse<List<TeacherResponse>>> listTeachers(
			@RequestParam(required = false) ApprovalStatus approvalStatus) {
		List<TeacherResponse> teachers = teacherService.listTeachers(approvalStatus)
			.stream()
			.map(TeacherController::toResponse)
			.toList();
		return ResponseEntity.ok(ApiResponse.success(teachers));
	}

	@GetMapping("/{id}")
	@PreAuthorize("@permissionCheckService.hasPermission('TEACHERS', 'VIEW')")
	public ResponseEntity<ApiResponse<TeacherResponse>> getTeacher(@PathVariable UUID id) {
		TeacherAccount account = teacherService.getTeacher(id);
		return ResponseEntity.ok(ApiResponse.success(toResponse(account)));
	}

	@PostMapping("/{id}/approve")
	@PreAuthorize("@permissionCheckService.hasPermission('TEACHERS', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<TeacherResponse>> approveTeacher(@PathVariable UUID id) {
		TeacherAccount account = teacherService.approveTeacher(id);
		return ResponseEntity.ok(ApiResponse.success(toResponse(account)));
	}

	@PostMapping("/{id}/reject")
	@PreAuthorize("@permissionCheckService.hasPermission('TEACHERS', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<TeacherResponse>> rejectTeacher(@PathVariable UUID id) {
		TeacherAccount account = teacherService.rejectTeacher(id);
		return ResponseEntity.ok(ApiResponse.success(toResponse(account)));
	}

	private static TeacherResponse toResponse(TeacherAccount account) {
		return new TeacherResponse(account.id(), account.name(), account.email(), account.approvalStatus(),
				account.accountStatus(), account.approvedBy(), account.approvedAt());
	}

}
