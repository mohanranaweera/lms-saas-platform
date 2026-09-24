package com.lms.usermanagement.student.web;

import com.lms.auditlogmanagement.api.AuditActivityEntry;
import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.usermanagement.student.service.BulkImportRowResult;
import com.lms.usermanagement.student.service.StudentAccount;
import com.lms.usermanagement.student.service.StudentBulkImportService;
import com.lms.usermanagement.student.service.StudentService;
import com.lms.usermanagement.student.web.dto.BulkImportRowResultResponse;
import com.lms.usermanagement.student.web.dto.StudentActivityResponse;
import com.lms.usermanagement.student.web.dto.StudentCreateRequest;
import com.lms.usermanagement.student.web.dto.StudentResponse;
import com.lms.usermanagement.student.web.dto.StudentUpdateRequest;
import com.lms.usermanagement.student.web.dto.TemporaryPasswordResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Student Management (MVP-006) endpoints. The staff-facing endpoints are
 * gated by {@code PermissionCheckService}'s {@code STUDENTS} domain area,
 * matching {@code docs/requirements/user-roles-and-permissions.md} §2's
 * matrix (Tenant Admin: view/create/edit/delete; Student Support:
 * view/create/edit; every other named staff role: view only). The
 * self-service {@code /me} endpoints are gated by Spring Security's
 * built-in {@code hasRole('STUDENT')} check against the {@code
 * ROLE_STUDENT} granted authority {@code JwtAuthenticationFilter} already
 * sets - deliberately NOT the {@code permissionCheckService} SpEL, since
 * that service's matrix has no entry for the {@code STUDENT} role at all
 * and would incorrectly default-deny a student viewing their own record.
 * Stays thin, delegates entirely to {@link StudentService}. No delete,
 * bulk-import, self-registration, teacher-roster, or history endpoint is
 * built in this pass (see MVP-006 plan - those depend on still-open
 * decisions or not-yet-built dependencies).
 */
@RestController
@RequestMapping("/api/v1/students")
public class StudentController {

	private final StudentService studentService;

	private final StudentBulkImportService studentBulkImportService;

	public StudentController(StudentService studentService, StudentBulkImportService studentBulkImportService) {
		this.studentService = studentService;
		this.studentBulkImportService = studentBulkImportService;
	}

	@PostMapping("/bulk-import")
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<List<BulkImportRowResultResponse>>> bulkImport(
			@RequestParam("file") MultipartFile file) {
		List<BulkImportRowResultResponse> response = studentBulkImportService.importCsv(file)
			.stream()
			.map(StudentController::toBulkImportResponse)
			.toList();
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@PostMapping
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<StudentResponse>> createStudent(
			@Valid @RequestBody StudentCreateRequest request) {
		StudentAccount account = studentService.createStudent(request.name(), request.email(), request.password());
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toResponse(account)));
	}

	@GetMapping
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'VIEW')")
	public ResponseEntity<ApiResponse<List<StudentResponse>>> listStudents() {
		List<StudentResponse> students = studentService.listStudents()
			.stream()
			.map(StudentController::toResponse)
			.toList();
		return ResponseEntity.ok(ApiResponse.success(students));
	}

	@GetMapping("/{id}")
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'VIEW')")
	public ResponseEntity<ApiResponse<StudentResponse>> getStudent(@PathVariable UUID id) {
		StudentAccount account = studentService.getStudent(id);
		return ResponseEntity.ok(ApiResponse.success(toResponse(account)));
	}

	@PatchMapping("/{id}")
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<StudentResponse>> updateStudent(@PathVariable UUID id,
			@Valid @RequestBody StudentUpdateRequest request) {
		StudentAccount account = studentService.updateStudent(id, request.name());
		return ResponseEntity.ok(ApiResponse.success(toResponse(account)));
	}

	@PostMapping("/{id}/activate")
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<StudentResponse>> activateStudent(@PathVariable UUID id) {
		StudentAccount account = studentService.activateStudent(id);
		return ResponseEntity.ok(ApiResponse.success(toResponse(account)));
	}

	@PostMapping("/{id}/deactivate")
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<StudentResponse>> deactivateStudent(@PathVariable UUID id) {
		StudentAccount account = studentService.deactivateStudent(id);
		return ResponseEntity.ok(ApiResponse.success(toResponse(account)));
	}

	@PostMapping("/{id}/reset-password")
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<TemporaryPasswordResponse>> resetPassword(@PathVariable UUID id) {
		String temporaryPassword = studentService.resetStudentPassword(id);
		return ResponseEntity.ok(ApiResponse.success(new TemporaryPasswordResponse(temporaryPassword)));
	}

	@GetMapping("/{id}/activity")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PageResponse<StudentActivityResponse>>> getActivity(@PathVariable UUID id,
			@PageableDefault(size = 20, sort = "occurredAt", direction = Sort.Direction.DESC) Pageable pageable) {
		PageResponse<AuditActivityEntry> page = studentService.listActivity(id, pageable);
		List<StudentActivityResponse> content = page.content().stream().map(StudentController::toActivityResponse).toList();
		return ResponseEntity.ok(ApiResponse.success(
				new PageResponse<>(content, page.page(), page.size(), page.totalElements(), page.totalPages())));
	}

	@GetMapping("/me")
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<StudentResponse>> getOwnProfile() {
		StudentAccount account = studentService.getOwnProfile();
		return ResponseEntity.ok(ApiResponse.success(toResponse(account)));
	}

	@PatchMapping("/me")
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<StudentResponse>> updateOwnProfile(
			@Valid @RequestBody StudentUpdateRequest request) {
		StudentAccount account = studentService.updateOwnProfile(request.name());
		return ResponseEntity.ok(ApiResponse.success(toResponse(account)));
	}

	private static StudentResponse toResponse(StudentAccount account) {
		return new StudentResponse(account.id(), account.name(), account.email(), account.roleCode(),
				account.status());
	}

	private static StudentActivityResponse toActivityResponse(AuditActivityEntry entry) {
		return new StudentActivityResponse(entry.id(), entry.actorId(), entry.actorDisplayName(), entry.action(),
				entry.reason(), entry.metadata(), entry.occurredAt());
	}

	private static BulkImportRowResultResponse toBulkImportResponse(BulkImportRowResult result) {
		return new BulkImportRowResultResponse(result.row(), result.status(), result.reason(), result.studentId());
	}

}
