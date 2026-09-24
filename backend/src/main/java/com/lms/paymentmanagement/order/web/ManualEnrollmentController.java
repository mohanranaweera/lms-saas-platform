package com.lms.paymentmanagement.order.web;

import com.lms.common.api.ApiResponse;
import com.lms.paymentmanagement.order.service.StudentEnrollmentService;
import com.lms.paymentmanagement.order.web.dto.StudentEnrollRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/students/{id}/enroll} (Wave 3, Student actions - staff
 * "enroll student in course"). Wave 3 fix-pass (architecture review, "3-module
 * dependency cycle") - this endpoint used to live on {@code user-management}'s
 * {@code StudentController} and call outward into this module's {@link
 * com.lms.paymentmanagement.api.ManualEnrollmentApi}, closing a {@code
 * payment-management -> enrollment-management -> user-management ->
 * payment-management} cycle. It now lives here instead, backed by {@link
 * StudentEnrollmentService}, which resolves the path's {@code student_profile}
 * id via {@code user-management}'s {@code StudentLookupApi} (an existing,
 * already-approved {@code api}-package-only dependency direction) rather than
 * {@code user-management} resolving it and delegating outward. The public API
 * contract - path, request body shape ({@code courseId}, {@code reason}), and
 * response shape ({@code ApiResponse<Void>}) - is byte-for-byte unchanged from
 * before this fix-pass; only the owning module moved. Stays thin, delegates
 * entirely to {@link StudentEnrollmentService}.
 */
@RestController
@RequestMapping("/api/v1/students")
public class ManualEnrollmentController {

	private final StudentEnrollmentService studentEnrollmentService;

	public ManualEnrollmentController(StudentEnrollmentService studentEnrollmentService) {
		this.studentEnrollmentService = studentEnrollmentService;
	}

	@PostMapping("/{id}/enroll")
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<Void>> enrollStudent(@PathVariable UUID id,
			@Valid @RequestBody StudentEnrollRequest request) {
		studentEnrollmentService.enroll(id, request.courseId(), request.reason());
		return ResponseEntity.ok(ApiResponse.success(null));
	}

}
