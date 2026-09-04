package com.lms.attendancemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lms.attendancemanagement.domain.AttendanceRecord;
import com.lms.attendancemanagement.domain.AttendanceStatus;
import com.lms.attendancemanagement.repository.AttendanceRecordRepository;
import com.lms.attendancemanagement.support.AttendanceAccessGuard;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.LessonOwnership;
import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

/**
 * Mockito-only unit coverage for {@link AttendanceMarkingService} (plan §18).
 * Uses a REAL {@link AttendanceAccessGuard} (backed by a mocked {@link
 * PermissionCheckService}), not a mocked guard, so the Teacher-ownership vs.
 * staff-matrix branching this service depends on is genuinely exercised here
 * - mirroring how {@code CourseAccessGuardTest} proves the guard's own logic,
 * but from the calling service's point of view (which real HTTP-layer
 * coverage in {@code AttendanceMarkingIntegrationTest} complements).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttendanceMarkingServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID COURSE_ID = UUID.randomUUID();

	private static final UUID MODULE_ID = UUID.randomUUID();

	private static final UUID SESSION_ID = UUID.randomUUID();

	private static final UUID TEACHER_ID = UUID.randomUUID();

	private static final UUID STUDENT_ID = UUID.randomUUID();

	@Mock
	private CourseLookupApi courseLookupApi;

	@Mock
	private EnrollmentAccessApi enrollmentAccessApi;

	@Mock
	private PermissionCheckService permissionCheckService;

	@Mock
	private AttendanceRecordRepository attendanceRecordRepository;

	@Mock
	private TenantContext tenantContext;

	private AttendanceMarkingService service;

	private static final LessonOwnership OWNERSHIP = new LessonOwnership(SESSION_ID, MODULE_ID, COURSE_ID,
			TEACHER_ID, true);

	@BeforeEach
	void setUp() {
		AttendanceAccessGuard guard = new AttendanceAccessGuard(permissionCheckService);
		service = new AttendanceMarkingService(courseLookupApi, enrollmentAccessApi, guard,
				attendanceRecordRepository, tenantContext);
		when(courseLookupApi.resolveLessonOwnership(SESSION_ID)).thenReturn(Optional.of(OWNERSHIP));
	}

	@AfterEach
	void clearPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	private static void setPrincipal(UUID userId, String role) {
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(userId, TENANT_ID, role, UUID.randomUUID()));
	}

	// ------------------------------------------------------------------
	// Atomic upsert: same call path for a first-time mark and a re-mark -
	// there is no more insert-vs-update branching to test at the mock level
	// (that decision now lives entirely inside the native query's ON
	// CONFLICT clause, covered at the Testcontainers level instead). What
	// IS still worth proving here is that a second markAttendance call for
	// the same (session, student) goes through the exact same
	// upsertRecord(...) call, carrying the NEW status through - i.e. a
	// re-mark is not silently dropped or routed through a different method.
	// ------------------------------------------------------------------

	@Test
	void reMarkingAnExistingSessionStudentRowGoesThroughTheSameAtomicUpsertCallWithTheNewStatus() {
		setPrincipal(TEACHER_ID, "TEACHER");
		when(enrollmentAccessApi.listCurrentlyEnrolledStudentIds(COURSE_ID)).thenReturn(List.of(STUDENT_ID));
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		AttendanceRecord firstState = mockAttendanceRecord(AttendanceStatus.PRESENT);
		AttendanceRecord secondState = mockAttendanceRecord(AttendanceStatus.ABSENT);
		when(attendanceRecordRepository.findBySessionIdAndStudentId(SESSION_ID, STUDENT_ID))
			.thenReturn(Optional.of(firstState), Optional.of(secondState));

		List<AttendanceMarkOutcome> first = service.markAttendance(SESSION_ID,
				List.of(new AttendanceMarkCommand(STUDENT_ID, AttendanceStatus.PRESENT)));
		List<AttendanceMarkOutcome> second = service.markAttendance(SESSION_ID,
				List.of(new AttendanceMarkCommand(STUDENT_ID, AttendanceStatus.ABSENT)));

		assertThat(first.get(0).success()).isTrue();
		assertThat(second.get(0).success()).isTrue();
		ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
		verify(attendanceRecordRepository, times(2)).upsertRecord(any(UUID.class), eq(TENANT_ID), eq(COURSE_ID),
				eq(SESSION_ID), eq(STUDENT_ID), statusCaptor.capture(), any(UUID.class), any(Instant.class),
				any(Instant.class));
		assertThat(statusCaptor.getAllValues()).containsExactly("PRESENT", "ABSENT");
	}

	// ------------------------------------------------------------------
	// markedBy/markedAt trusted-context provenance, and server-side courseId
	// derivation (never client-supplied - plan §12).
	// ------------------------------------------------------------------

	@Test
	void newRowsAlwaysStampMarkedByAndMarkedAtFromTheAuthenticatedContextNeverFromTheCommand() {
		setPrincipal(TEACHER_ID, "TEACHER");
		when(enrollmentAccessApi.listCurrentlyEnrolledStudentIds(COURSE_ID)).thenReturn(List.of(STUDENT_ID));
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		AttendanceRecord persisted = mockAttendanceRecord(AttendanceStatus.PRESENT);
		when(attendanceRecordRepository.findBySessionIdAndStudentId(SESSION_ID, STUDENT_ID))
			.thenReturn(Optional.of(persisted));
		Instant before = Instant.now();

		service.markAttendance(SESSION_ID, List.of(new AttendanceMarkCommand(STUDENT_ID, AttendanceStatus.PRESENT)));

		Instant after = Instant.now();
		ArgumentCaptor<UUID> markedByCaptor = ArgumentCaptor.forClass(UUID.class);
		ArgumentCaptor<Instant> markedAtCaptor = ArgumentCaptor.forClass(Instant.class);
		// AttendanceMarkCommand carries no markedBy/markedAt field at all - the
		// only possible source is AuthenticatedPrincipalHolder/the server clock.
		verify(attendanceRecordRepository).upsertRecord(any(UUID.class), eq(TENANT_ID), eq(COURSE_ID), eq(SESSION_ID),
				eq(STUDENT_ID), eq("PRESENT"), markedByCaptor.capture(), markedAtCaptor.capture(), any(Instant.class));
		assertThat(markedByCaptor.getValue()).isEqualTo(TEACHER_ID);
		assertThat(markedAtCaptor.getValue()).isBetween(before, after);
	}

	@Test
	void courseIdIsAlwaysDerivedServerSideFromTheResolvedLessonOwnershipNeverAnyOtherSource() {
		setPrincipal(TEACHER_ID, "TEACHER");
		when(enrollmentAccessApi.listCurrentlyEnrolledStudentIds(COURSE_ID)).thenReturn(List.of(STUDENT_ID));
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		AttendanceRecord persisted = mockAttendanceRecord(AttendanceStatus.LATE);
		when(attendanceRecordRepository.findBySessionIdAndStudentId(SESSION_ID, STUDENT_ID))
			.thenReturn(Optional.of(persisted));

		service.markAttendance(SESSION_ID, List.of(new AttendanceMarkCommand(STUDENT_ID, AttendanceStatus.LATE)));

		ArgumentCaptor<UUID> courseIdCaptor = ArgumentCaptor.forClass(UUID.class);
		ArgumentCaptor<UUID> sessionIdCaptor = ArgumentCaptor.forClass(UUID.class);
		verify(attendanceRecordRepository).upsertRecord(any(UUID.class), eq(TENANT_ID), courseIdCaptor.capture(),
				sessionIdCaptor.capture(), eq(STUDENT_ID), eq("LATE"), any(UUID.class), any(Instant.class),
				any(Instant.class));
		assertThat(courseIdCaptor.getValue()).isEqualTo(OWNERSHIP.courseId());
		assertThat(sessionIdCaptor.getValue()).isEqualTo(SESSION_ID);
	}

	// ------------------------------------------------------------------
	// Teacher-ownership guard branch.
	// ------------------------------------------------------------------

	@Test
	void teacherMarkingALessonOutsideTheirOwnedCourseIsRejectedBeforeAnyRosterOrPersistenceCallHappens() {
		UUID nonOwningTeacherId = UUID.randomUUID();
		setPrincipal(nonOwningTeacherId, "TEACHER");

		assertThatThrownBy(() -> service.markAttendance(SESSION_ID,
				List.of(new AttendanceMarkCommand(STUDENT_ID, AttendanceStatus.PRESENT))))
			.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(enrollmentAccessApi);
		verify(attendanceRecordRepository, never()).save(any());
		verifyNoInteractions(permissionCheckService);
	}

	@Test
	void teacherMarkingALessonWithinTheirOwnedCourseIsAllowed() {
		setPrincipal(TEACHER_ID, "TEACHER");
		when(enrollmentAccessApi.listCurrentlyEnrolledStudentIds(COURSE_ID)).thenReturn(List.of(STUDENT_ID));
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		AttendanceRecord persisted = mockAttendanceRecord(AttendanceStatus.PRESENT);
		when(attendanceRecordRepository.findBySessionIdAndStudentId(SESSION_ID, STUDENT_ID))
			.thenReturn(Optional.of(persisted));

		List<AttendanceMarkOutcome> outcomes = service.markAttendance(SESSION_ID,
				List.of(new AttendanceMarkCommand(STUDENT_ID, AttendanceStatus.PRESENT)));

		assertThat(outcomes).hasSize(1);
		assertThat(outcomes.get(0).success()).isTrue();
		verifyNoInteractions(permissionCheckService);
	}

	// ------------------------------------------------------------------
	// Staff (non-Teacher) matrix branch - a distinct code path.
	// ------------------------------------------------------------------

	@Test
	void staffWithAttendanceCreateEditGrantMarksRegardlessOfCourseOwnership() {
		UUID operatorId = UUID.randomUUID();
		setPrincipal(operatorId, "ATTENDANCE_OPERATOR");
		when(enrollmentAccessApi.listCurrentlyEnrolledStudentIds(COURSE_ID)).thenReturn(List.of(STUDENT_ID));
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		AttendanceRecord persisted = mockAttendanceRecord(AttendanceStatus.PRESENT);
		when(attendanceRecordRepository.findBySessionIdAndStudentId(SESSION_ID, STUDENT_ID))
			.thenReturn(Optional.of(persisted));
		// permissionCheckService.requirePermission is a void method - the
		// lenient default (do nothing) models a granted permission.

		List<AttendanceMarkOutcome> outcomes = service.markAttendance(SESSION_ID,
				List.of(new AttendanceMarkCommand(STUDENT_ID, AttendanceStatus.PRESENT)));

		assertThat(outcomes).hasSize(1);
		assertThat(outcomes.get(0).success()).isTrue();
		verify(permissionCheckService).requirePermission(DomainArea.ATTENDANCE, PermissionAction.CREATE_EDIT);
	}

	@Test
	void readOnlyAuditorCannotMarkAttendance() {
		UUID auditorId = UUID.randomUUID();
		setPrincipal(auditorId, "READ_ONLY_AUDITOR");
		doThrow(new AccessDeniedException("You do not have permission to perform this action"))
			.when(permissionCheckService)
			.requirePermission(DomainArea.ATTENDANCE, PermissionAction.CREATE_EDIT);

		assertThatThrownBy(() -> service.markAttendance(SESSION_ID,
				List.of(new AttendanceMarkCommand(STUDENT_ID, AttendanceStatus.PRESENT))))
			.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(enrollmentAccessApi);
		verify(attendanceRecordRepository, never()).save(any());
	}

	// ------------------------------------------------------------------
	// Roster-bypass guard: a studentId not on the current roster, including
	// an expired-enrollment student - batch-partial, never a whole-batch
	// exception (plan §13/§15).
	// ------------------------------------------------------------------

	@Test
	void aStudentNotOnTheCurrentRosterIsRejectedAsAPerRowOutcomeNotAWholeBatchException() {
		setPrincipal(TEACHER_ID, "TEACHER");
		UUID expiredEnrollmentStudentId = UUID.randomUUID();
		// Only STUDENT_ID is currently enrolled - the expired-enrollment
		// student is excluded from the live roster, exactly like a student
		// who was never enrolled at all.
		when(enrollmentAccessApi.listCurrentlyEnrolledStudentIds(COURSE_ID)).thenReturn(List.of(STUDENT_ID));
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		AttendanceRecord persisted = mockAttendanceRecord(AttendanceStatus.PRESENT);
		when(attendanceRecordRepository.findBySessionIdAndStudentId(SESSION_ID, STUDENT_ID))
			.thenReturn(Optional.of(persisted));

		List<AttendanceMarkOutcome> outcomes = service.markAttendance(SESSION_ID,
				List.of(new AttendanceMarkCommand(STUDENT_ID, AttendanceStatus.PRESENT),
						new AttendanceMarkCommand(expiredEnrollmentStudentId, AttendanceStatus.ABSENT)));

		assertThat(outcomes).hasSize(2);
		AttendanceMarkOutcome validRow = outcomes.get(0);
		assertThat(validRow.studentId()).isEqualTo(STUDENT_ID);
		assertThat(validRow.success()).isTrue();
		assertThat(validRow.record()).isNotNull();

		AttendanceMarkOutcome rejectedRow = outcomes.get(1);
		assertThat(rejectedRow.studentId()).isEqualTo(expiredEnrollmentStudentId);
		assertThat(rejectedRow.success()).isFalse();
		assertThat(rejectedRow.record()).isNull();
		assertThat(rejectedRow.reason()).isNotBlank();

		// Exactly one atomic upsert call - for the valid row only.
		verify(attendanceRecordRepository, times(1)).upsertRecord(any(UUID.class), any(UUID.class), any(UUID.class),
				any(UUID.class), any(UUID.class), any(String.class), any(UUID.class), any(Instant.class),
				any(Instant.class));
	}

	// ------------------------------------------------------------------
	// Test helper: a mocked post-write AttendanceRecord state, standing in
	// for what a fresh findBySessionIdAndStudentId read would return after
	// the atomic upsertRecord(...) write (which itself returns void, so
	// there is no entity to build a response view from at the mock level).
	// ------------------------------------------------------------------

	private static AttendanceRecord mockAttendanceRecord(AttendanceStatus status) {
		AttendanceRecord record = mock(AttendanceRecord.class);
		when(record.getId()).thenReturn(UUID.randomUUID());
		when(record.getCourseId()).thenReturn(COURSE_ID);
		when(record.getSessionId()).thenReturn(SESSION_ID);
		when(record.getStudentId()).thenReturn(STUDENT_ID);
		when(record.getStatus()).thenReturn(status);
		when(record.getMarkedBy()).thenReturn(TEACHER_ID);
		when(record.getMarkedAt()).thenReturn(Instant.now());
		when(record.getCreatedAt()).thenReturn(Instant.now());
		when(record.getUpdatedAt()).thenReturn(Instant.now());
		return record;
	}

}
