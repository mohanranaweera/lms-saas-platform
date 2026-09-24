package com.lms.paymentmanagement.order.service;

import com.lms.common.error.NotFoundException;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.paymentmanagement.api.ManualEnrollmentApi;
import com.lms.usermanagement.api.StudentLookupApi;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Backs {@code POST /api/v1/students/{id}/enroll} (Wave 3, Student actions -
 * staff "enroll student in course"). Wave 3 fix-pass (architecture review) -
 * this thin orchestration used to live in {@code user-management}'s {@code
 * StudentService}, which called {@code payment-management}'s {@link
 * ManualEnrollmentApi} - closing a {@code payment-management ->
 * enrollment-management -> user-management -> payment-management} module
 * dependency cycle (both {@code enrollment-management} and {@code
 * payment-management} already depend on {@code user-management}'s {@link
 * StudentLookupApi} for their own studentId-scoped reads/roster). Moving the
 * HTTP entrypoint here - so {@code payment-management} depends ON {@code
 * user-management} (via {@link StudentLookupApi}, an existing, approved {@code
 * api}-package-only dependency direction already used elsewhere in this
 * module) rather than the reverse - collapses the cycle. The public API
 * contract ({@code POST /api/v1/students/{id}/enroll}, same request/response
 * shape) is unchanged; only which module owns the controller/service moved.
 *
 * <p>Resolves the path's {@code student_profile} id to the opaque
 * cross-domain {@code studentId} via {@link StudentLookupApi#resolveUserId},
 * mirroring every other studentId-scoped cross-domain read added this wave
 * (enrollment history, ledger, attendance report, exam attempts, roster) -
 * never a client-supplied {@code tenant_user} id, and never {@code
 * user-management} resolving it and delegating outward (the shape this fix
 * removes).
 *
 * <p>Independently re-checks {@code STUDENTS}/{@code CREATE_EDIT} (defense in
 * depth on top of {@code StudentEnrollmentController}'s {@code @PreAuthorize}
 * gate, mirroring the original {@code StudentService#enrollStudent}'s exact
 * discipline) BEFORE resolving the student id, so a caller without the grant
 * never learns whether {@code id} resolves to a real student in this tenant.
 * {@link ManualEnrollmentApi#grantEnrollment} independently re-checks {@code
 * PAYMENTS_SLIPS}/{@code APPROVE} on top of this (unchanged from before this
 * fix-pass) and performs the actual atomic Order-&gt;Payment-&gt;ledger-&gt;
 * Enrollment write plus its own audit-log write - this class deliberately
 * does NOT write a second audit-log entry for the same action (Wave 3
 * fix-pass finding: the pre-fix code double-wrote one entry here targeting
 * {@code student_profile} and another inside {@code ManualEnrollmentService}
 * targeting {@code payment} - no other flow in this codebase writes two
 * audit entries for one privileged action, so this consolidates to the
 * single, already-atomic write inside {@code ManualEnrollmentService}).
 */
@Service
public class StudentEnrollmentService {

	private final StudentLookupApi studentLookupApi;

	private final ManualEnrollmentApi manualEnrollmentApi;

	private final PermissionCheckService permissionCheckService;

	public StudentEnrollmentService(StudentLookupApi studentLookupApi, ManualEnrollmentApi manualEnrollmentApi,
			PermissionCheckService permissionCheckService) {
		this.studentLookupApi = studentLookupApi;
		this.manualEnrollmentApi = manualEnrollmentApi;
		this.permissionCheckService = permissionCheckService;
	}

	public void enroll(UUID studentProfileId, UUID courseId, String reason) {
		permissionCheckService.requirePermission(DomainArea.STUDENTS, PermissionAction.CREATE_EDIT);
		UUID studentId = studentLookupApi.resolveUserId(studentProfileId)
			.orElseThrow(() -> new NotFoundException("Student account not found"));
		manualEnrollmentApi.grantEnrollment(studentId, courseId, reason);
	}

}
