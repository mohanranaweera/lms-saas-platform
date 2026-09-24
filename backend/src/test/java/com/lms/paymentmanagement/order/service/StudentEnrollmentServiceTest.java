package com.lms.paymentmanagement.order.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.error.NotFoundException;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.paymentmanagement.api.ManualEnrollmentApi;
import com.lms.usermanagement.api.StudentLookupApi;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * Mockito-only unit coverage for {@link StudentEnrollmentService} - Wave 3
 * fix-pass (architecture review, "3-module dependency cycle"). This class
 * moved here from {@code user-management}'s {@code
 * StudentService#enrollStudent} (see {@link StudentEnrollmentService}'s own
 * javadoc for the full rationale); this test mirrors what {@code
 * StudentServiceTest#enrollStudentDelegatesToManualEnrollmentApiWithTheReasonAndAudits}
 * used to cover there, now against the new module boundary: permission-checked
 * BEFORE resolving the student id, {@code student_profile} id resolved to the
 * opaque {@code studentId} via {@link StudentLookupApi}, and delegation to
 * {@link ManualEnrollmentApi} unchanged.
 */
@ExtendWith(MockitoExtension.class)
class StudentEnrollmentServiceTest {

	@Mock
	private StudentLookupApi studentLookupApi;

	@Mock
	private ManualEnrollmentApi manualEnrollmentApi;

	@Mock
	private PermissionCheckService permissionCheckService;

	private StudentEnrollmentService service;

	@BeforeEach
	void setUp() {
		service = new StudentEnrollmentService(studentLookupApi, manualEnrollmentApi, permissionCheckService);
	}

	@Test
	void enrollChecksPermissionBeforeResolvingTheStudentId() {
		UUID studentProfileId = UUID.randomUUID();
		UUID courseId = UUID.randomUUID();
		doThrow(new AccessDeniedException("denied")).when(permissionCheckService)
			.requirePermission(DomainArea.STUDENTS, PermissionAction.CREATE_EDIT);

		assertThatThrownBy(() -> service.enroll(studentProfileId, courseId, "scholarship"))
			.isInstanceOf(AccessDeniedException.class);

		verify(studentLookupApi, never()).resolveUserId(any());
		verify(manualEnrollmentApi, never()).grantEnrollment(any(), any(), any());
	}

	@Test
	void enrollOfANonexistentOrCrossTenantStudentProfileIdThrowsNotFoundWithoutDelegating() {
		UUID studentProfileId = UUID.randomUUID();
		UUID courseId = UUID.randomUUID();
		when(studentLookupApi.resolveUserId(studentProfileId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.enroll(studentProfileId, courseId, "scholarship"))
			.isInstanceOf(NotFoundException.class);

		verify(manualEnrollmentApi, never()).grantEnrollment(any(), any(), any());
	}

	@Test
	void enrollResolvesTheStudentIdAndDelegatesToManualEnrollmentApi() {
		UUID studentProfileId = UUID.randomUUID();
		UUID studentId = UUID.randomUUID();
		UUID courseId = UUID.randomUUID();
		when(studentLookupApi.resolveUserId(studentProfileId)).thenReturn(Optional.of(studentId));

		service.enroll(studentProfileId, courseId, "scholarship");

		verify(manualEnrollmentApi).grantEnrollment(studentId, courseId, "scholarship");
	}

}
